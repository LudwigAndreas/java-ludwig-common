# messaging-db-spring-boot-starter

[Русская версия](README.ru.md)

A temporary, removable stand-in for a Kafka cluster you can write to.

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
 service A (outbox, transport: kafka) ──► message_log (shared PostgreSQL) ──► service B, C … @KafkaListener
                                            ▲ one position per consumer group: every group gets every record
 external Kafka ────────────────────────────┼──────────────────────────────► your listeners, unchanged
                                            │
 every dead letter, from either source ─────┘
```

- **Writes.** Every record the outbox would send over its `kafka` transport is appended to the log
  instead, with the same topic, key, headers and payload. While this module is active, nothing the
  outbox or a builder-built listener factory writes goes to a broker. That includes **dead letters of
  topics read from external Kafka**, which land in
  the log as `<topic>.dlt`, because there is no cluster this side can write them to.
- **Reads.** A listener's topics that are listed in `ludwig.messaging.db.consume` are read from the log.
  Every other topic is read from Kafka as before. One listener may subscribe to topics of both kinds.

## Adopting it

Add the dependency and name the topics your services exchange:

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>messaging-db-spring-boot-starter</artifactId>
</dependency>
```

```yaml
ludwig:
  messaging:
    db:
      consume:
        - catalog.products
        - catalog.products.dlt   # read your own dead letters back, when something consumes them
```

That is the whole adoption. Listeners, their `containerFactory` beans built with
`ListenerContainerFactoryBuilder`, and the outbox routes, which keep saying `transport: kafka`, stay
exactly as they are. The tables are created on first start.

The producing and the consuming service must both carry the dependency. A producer without it writes
to Kafka, and a consumer reading the topic from the log will never see that record.

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

A service fails to start, with the consumer and topic named, when:

- a consumer with `retry.non-blocking=true` reads a log topic. Retry topics need a broker.
- a listener subscribes by `topicPattern` and the pattern matches a listed topic. A pattern cannot be
  resolved against the log, so name the topics instead.
- a listener reads a log topic through a container factory that `ListenerContainerFactoryBuilder` did
  not build. Without the builder there are no consumer settings to apply.
- a listener reads a log topic without a group id.

## Configuration reference

| Property | Default | |
|---|---|---|
| `ludwig.messaging.db.mode` | `active` | `active`: writes go to the log. `draining`: writes go to Kafka, and listed topics are read from both. See [Moving to a real cluster](#moving-to-a-real-cluster) |
| `ludwig.messaging.db.consume` | empty | The topics read from the log |
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

1. **Set `ludwig.messaging.db.mode=draining` on every service**, in any order. From then on writes go
   to Kafka, and listed topics are read from **both** the log and Kafka, so nothing already in the log
   is lost.
2. **Wait until `ludwig.messaging.db.lag` is 0 for every group.**
3. **Remove the dependency and the `ludwig.messaging.db.*` properties.** Nothing else changes: the
   listeners, factory beans and outbox routes were Kafka's all along.
4. **Drop the schema.** Liquibase cannot do this, because the changesets leave with the jar:

   ```sql
   DROP SCHEMA ludwig_messaging CASCADE;
   DELETE FROM databasechangelog WHERE author = 'ludwig-messaging-db';   -- in each service's changelog table
   ```

**Ordering during the switch.** While a topic drains, one key can have records both in the log
backlog and on Kafka, and the two containers interleave them. Per-key ordering holds before and after
the switch, not during it. Switch when the topic is quiet if order matters to a consumer. Dedup covers
duplicates, not reordering.

**A separate cluster.** In `draining` mode, writes go to `spring.kafka.bootstrap-servers`. When the
cluster this side owns is not the one the external topics are read from, that is still to be decided:
the outbox's template will need its own address.

## How it attaches

This module edits nothing in the modules it changes the behaviour of:

- **The outbox.** The outbox registers its Kafka dispatcher only when no bean named
  `kafkaOutboxDispatcher` exists. This module registers that name first, as the outbox's own
  `KafkaOutboxDispatcher` over a `KafkaTemplate` whose producer appends to the log. Header building
  therefore stays in one place, and the outbox's registry still sees exactly one dispatcher for the
  transport.
- **The listeners.** spring-kafka resolves `@KafkaListener(containerFactory = "...")` by name, as a
  `KafkaListenerContainerFactory`. A `BeanPostProcessor` replaces every factory bean with a routing
  factory standing in for it. The routing factory builds the original's Kafka container for the
  endpoint, then reads that container's listener, deserializers and transaction manager. The same
  post-processor proxies `ListenerContainerFactoryBuilder` to remember which consumer name each factory
  was built for, because the name is what the retry and dead-letter settings are resolved by.
- **Removability is checked.** The `kafka.nothing-depends-on-the-db-log-transport` architecture rule
  fails any module that names a class of this one.

## What this module deliberately does not do

- **Be a broker.** There is no compaction, no transactions spanning the log and the listener, no
  seeking, no `LISTEN/NOTIFY` push and no retry topics. Throughput is what one shared PostgreSQL gives:
  hundreds of records a second, not Kafka's numbers.
- **Write to external topics.** Nothing on this side writes to them, so there is no per-topic write
  setting.
- **Stay.** It is a stand-in for one year. The removal path above is the design, not an afterthought.
