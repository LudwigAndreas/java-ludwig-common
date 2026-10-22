# messaging-db-spring-boot-starter

[Русская версия](README.ru.md)

A temporary, removable stand-in for the Kafka clusters you own but do not have yet.

Services already produce through `outbox-spring-boot-starter` and consume through `@KafkaListener` and
`messaging-spring-boot-starter`. Until there is a cluster this side owns, this module gives those writes
somewhere to go: a Kafka-shaped log in the PostgreSQL database every service shares. It is a dependency
you add now and remove later. Nothing else in a service changes in either direction.

- [What it does](#what-it-does)
- [Adopting it](#adopting-it)
- [What a consumer keeps](#what-a-consumer-keeps)
- [What is refused](#what-is-refused)
- [Configuration reference](#configuration-reference)
- [Operating it](#operating-it)
- [**Moving to a real cluster**](#moving-to-a-real-cluster)
- [How it attaches](#how-it-attaches)
- [What this module deliberately does not do](#what-this-module-deliberately-does-not-do)

## What it does

```
 service A (outbox, transport: kafka:internal) ──► message_log (shared PostgreSQL) ──► consumers on internal
                                                    ▲ one position per consumer group: every group gets every record
 a partner's cluster ───────────────────────────────┼──────────────────────────────► its consumers, unchanged
                                                    │
 every dead letter addressed to internal ───────────┘
```

- **Writes.** Every record the platform produces to a replaced cluster - one named in
  `ludwig.messaging.db.clusters`, `internal` by default - is appended to the log instead, with the same
  topic, key, headers and payload. That covers outbox events over `kafka:<cluster>` and every dead letter
  addressed to the cluster. No connection to it is attempted. Every other cluster gets its writes as before.
- **Reads.** A consumer whose cluster (`ludwig.messaging.consumers.<name>.cluster`) is replaced reads all of
  its topics from the log. Every other consumer reads its own cluster. A consumer of a partner's cluster
  sends its dead letters to the log with `dead-letter.cluster: internal`, because there is no cluster of
  this side's own to write them to.

## Adopting it

Add the dependency, and declare the cluster you will own as `add-kafka-clusters` declares any cluster:

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>messaging-db-spring-boot-starter</artifactId>
</dependency>
```

```yaml
ludwig:
  kafka:
    clusters:
      internal:
        client-id: catalog-service   # declared; it needs no address until draining
  messaging:
    db:
      clusters: [internal]           # the default
    consumers:
      catalog:
        cluster: internal
  outbox:
    routes:
      products:
        transport: kafka:internal
```

That is the whole adoption. Listeners, their `containerFactory` beans built with
`ListenerContainerFactoryBuilder`, and the outbox routes are written for the cluster, not for the log, so
the day it exists giving it an address is the only change. The tables are created on first start.

The producing and the consuming service must both carry the dependency. A producer without it writes
to the cluster, and a consumer reading that cluster from the log will never see that record.

## What a consumer keeps

The log container runs the listener spring-kafka already adapted for the endpoint, behind the same
objects messaging's builder gave the Kafka container:

| Behaviour | On the log |
|---|---|
| Record shape | A real `ConsumerRecord`: key, headers and value as written, and the log's topic, partition and offset. `EnvelopeReader` works unchanged |
| Partitioning | Kafka's own default partitioner (`murmur2(key) % partitions`). A record with no key goes to partition 0 |
| Ordering | Within a group, one consumer at a time per partition. A record is not delivered before every earlier record in its partition is handled or dead-lettered |
| Fan-out | Each `groupId` keeps its own position per partition. A new group starts at `auto-offset-reset`: `earliest` reads the log from the start, `latest` reads only new records |
| Delivery | At least once. The position moves only after the listener returns or the record is dead-lettered. A crashed instance redelivers at most the one record it was handling |
| Retry | The consumer's resolved `ludwig.messaging.consumers.<name>.retry.*`: the same attempt count, the same exponential backoff. The partition is held while it waits, so no thread sleeps |
| Not retried | `DeserializationException` (a poison payload, an unsupported event version) and `MessageConversionException` go straight to the dead letter |
| Dead letter | `<topic><suffix>` in the log, with the original key, payload **bytes** and headers, plus `kafka_dlt-original-topic/-partition/-offset` and `kafka_dlt-exception-fqcn`. The exception's message is not written, because it quotes payloads. Counted and audited by messaging's own recoverer |
| Failed dead letter | The position does not move, `ludwig.messaging.dropped` and the dropped audit event are recorded, and the record is tried again after the longest backoff |
| Dedup, correlation | The `RecordFilterStrategy` and every `RecordInterceptor` in the context apply, and the container transaction manager is used when dedup asks for one |

## What is refused

A service fails to start, naming what to fix, when:

- `ludwig.messaging.db.clusters` names a cluster that is not declared under `ludwig.kafka.clusters`.
- a consumer on a replaced cluster sets `retry.non-blocking=true`. Retry topics need a broker.
- a listener on a replaced cluster subscribes by `topicPattern`. A pattern cannot be resolved against
  the log, so name the topics instead.
- a listener reads `default` through a container factory that `ListenerContainerFactoryBuilder` did not
  build, while `default` is replaced. Without the builder there are no consumer settings to apply.
- a listener on a replaced cluster has no group id.

## Configuration reference

| Property | Default | |
|---|---|---|
| `ludwig.messaging.db.mode` | `active` | `active`: the replaced clusters' writes go to the log. `draining`: they go to each cluster's own address, and its consumers read both. See [Moving to a real cluster](#moving-to-a-real-cluster) |
| `ludwig.messaging.db.clusters` | `[internal]` | The clusters the log stands in for, by the names `ludwig.kafka.clusters` declares (`default` is `spring.kafka.*`). Each must be declared |
| `ludwig.messaging.db.partitions` | `8` | Partitions per topic. **Fixed once records exist**: changing it moves keys to other partitions, so per-key ordering across the change is lost |
| `ludwig.messaging.db.poll-interval` | `1s` | How long an idle worker waits before polling again |
| `ludwig.messaging.db.retention` | `7d` | Records older than this are purged, **consumed or not** |
| `ludwig.messaging.db.purge-interval` | `1h` | How often the purge runs |
| `ludwig.messaging.db.schema` | `ludwig_messaging` | The schema the tables live in. Every service must use the same value. **Fixed once deployed**: the changesets are checksummed with the name substituted |

Workers per listener follow the listener's `concurrency`, exactly as Kafka consumer threads do.

## Operating it

- **Tables.** `message_log` holds the records, `partition_head` holds each partition's next offset (its
  row lock serialises writers, so offsets never become visible out of order), and `consumer_offset`
  holds each group's position (its row lock is the partition assignment).
- **Connections.** A worker holds one pooled connection while it handles a record, and the listener's
  own transaction uses another. Size the pool for `concurrency × 2` per log listener, on top of
  whatever the service already needs.
- **Load.** An idle worker runs one indexed query per poll interval.
- **Purge.** Unconsumed records are purged on purpose: the database is shared, and a group that stopped
  consuming must not grow it without bound. Before deleting, the purge logs a warning per group and
  topic saying how many records that group never consumed. That warning is the signal a consumer has
  stopped.
- **Lag.** `ludwig.messaging.db.lag{group,topic}` is the number of records a group has not consumed.
  It is refreshed every 30 seconds, so a scrape never waits on the database.
- **Schema install.** Every service runs the changesets with its own changelog table. Each changeset
  is `IF NOT EXISTS` and takes one advisory lock first, so services may start in any order, or at the
  same moment.

## Moving to a real cluster

The order matters, and it is short:

1. **Give each replaced cluster its address**, `ludwig.kafka.clusters.<name>.bootstrap-servers` and
   whatever security it needs, and **set `ludwig.messaging.db.mode=draining` on every service**, in any
   order. From then on the cluster's writes go to that address, and its consumers read **both** the log
   and the cluster, so nothing already in the log is lost.
2. **Wait until `ludwig.messaging.db.lag` is 0 for every group.**
3. **Remove the dependency and the `ludwig.messaging.db.*` properties.** Nothing else changes: the
   listeners, factory beans and outbox routes were the cluster's all along.
4. **Drop the schema.** Liquibase cannot do this, because the changesets leave with the jar:

   ```sql
   DROP SCHEMA ludwig_messaging CASCADE;
   DELETE FROM databasechangelog WHERE author = 'ludwig-messaging-db';   -- in each service's changelog table
   ```

**Ordering during the switch.** While a topic drains, one key can have records both in the log
backlog and on Kafka, and the two containers interleave them. Per-key ordering holds before and after
the switch, not during it. Switch when the topic is quiet if order matters to a consumer. Dedup covers
duplicates, not reordering.

**Every replaced cluster moves at once.** `mode` is one switch for all of `clusters`. With more than one
replaced cluster, give each its address before switching; a cluster cannot drain while another stays on
the log.

## How it attaches

This module edits nothing in the modules it changes the behaviour of:

- **Writes.** messaging's `KafkaClusterProducers` is the one seam every platform producer comes from:
  the outbox's templates and every dead-letter template. This module registers it first, decorated: a
  replaced cluster gets a producer factory whose producers serialize as asked and append the bytes to the
  log. Header building and serializer choices stay in their own modules, and the
  `kafka.producers-come-from-the-cluster-seam` rule keeps the seam the only place.
- **The listeners.** spring-kafka resolves `@KafkaListener(containerFactory = "...")` by name, as a
  `KafkaListenerContainerFactory`. A `BeanPostProcessor` replaces every factory bean with a routing
  factory standing in for it. For a consumer on a replaced cluster, the routing factory builds the
  original's Kafka container for the endpoint, then reads that container's listener, deserializers and
  transaction manager. Any other consumer gets the original's container untouched. The same
  post-processor proxies `ListenerContainerFactoryBuilder` to remember which consumer name each factory
  was built for, because the name is what the retry and dead-letter settings are resolved by.
- **Removability is checked.** The `kafka.nothing-depends-on-the-db-log-transport` architecture rule
  fails any module that names a class of this one.

## What this module deliberately does not do

- **Be a broker.** There is no compaction, no transactions spanning the log and the listener, no
  seeking, no `LISTEN/NOTIFY` push and no retry topics. Throughput is what one shared PostgreSQL gives:
  hundreds of records a second, not Kafka's numbers.
- **Replace part of a cluster.** A cluster is replaced whole. There is no per-topic setting, and a
  listener reads one cluster.
- **Stay.** It is a stand-in for one year. The removal path above is the design, not an afterthought.
