# messaging-db-spring-boot-starter

***English** · [Русский](README.ru.md)*

A removable stand-in for a Kafka cluster this side will own but does not have yet. It stores a
Kafka-shaped log in the PostgreSQL database the services share. Services keep producing through
[`outbox-spring-boot-starter`](../outbox-spring-boot-starter) and consuming through `@KafkaListener` and
[`messaging-spring-boot-starter`](../messaging-spring-boot-starter); only the dependency is added now
and removed later. Nothing else in a service changes in either direction.

| | |
|---|---|
| Role | starter (temporary transport) |
| Depends on (in-repo) | [`job-core`](../job-core), [`messaging-spring-boot-starter`](../messaging-spring-boot-starter) |
| Used by | none in this repository (added by services that have no own Kafka cluster) |
| Surfaces | Kafka-compatible log in PostgreSQL |

```
 service A (outbox, transport: kafka:internal) ──► message_log (shared PostgreSQL) ──► consumers on internal
                                                    ▲ one position per consumer group: every group gets every record
 a partner's cluster ───────────────────────────────┼──────────────────────────────► its consumers, unchanged
                                                    │
 every dead letter addressed to internal ───────────┘
```

## Usage

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>messaging-db-spring-boot-starter</artifactId>
</dependency>
```

Declare the cluster you will own exactly as any other cluster is declared, and name it as replaced:

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
`ListenerContainerFactoryBuilder`, and outbox routes are written for the cluster, so on the day the
cluster exists the only change is giving it an address.

**Both the producing and the consuming service must carry the dependency.** A producer without it
writes to the real cluster, and a consumer reading that cluster from the log never sees the record.

- **Writes.** Every record the platform produces to a replaced cluster (outbox events over
  `kafka:<cluster>`, every dead letter addressed to it) is appended to the log with the same topic, key,
  headers and payload. No connection to the cluster is attempted. Other clusters are unaffected.
- **Reads.** A consumer whose `ludwig.messaging.consumers.<name>.cluster` is replaced reads all its topics
  from the log. A consumer of a partner's cluster can send its dead letters to the log with
  `dead-letter.cluster: internal`.

### What a consumer keeps

| Behaviour | On the log |
|---|---|
| Record shape | A real `ConsumerRecord`: key, headers and value as written, plus the log's topic, partition and offset. `EnvelopeReader` works unchanged |
| Partitioning | Kafka's default partitioner (`murmur2(key) % partitions`). A record with no key goes to partition 0 |
| Ordering | Within a group, one consumer at a time per partition. A record is delivered only after every earlier record in its partition is handled or dead-lettered |
| Fan-out | Each `groupId` keeps its own position per partition. A new group starts at `auto-offset-reset`: `earliest` reads from the start, `latest` only new records |
| Delivery | At least once. The position moves only after the listener returns or the record is dead-lettered. A crashed instance redelivers at most the record it was handling |
| Retry | The consumer's resolved `retry.*` settings: same attempt count, same exponential backoff. The partition is held while waiting; no thread sleeps |
| Not retried | `DeserializationException` (poison payload, unsupported version) and `MessageConversionException` go straight to the dead letter |
| Dead letter | `<topic><suffix>` in the log, with the original key, payload **bytes** and headers, plus `kafka_dlt-original-topic/-partition/-offset` and `kafka_dlt-exception-fqcn`. The exception message is not written (it may quote payloads). Counted and audited by messaging's recoverer |
| Failed dead letter | The position does not move; `ludwig.messaging.dropped` and the dropped audit event are recorded; the record is retried after the longest backoff |
| Dedup, correlation | The context's `RecordFilterStrategy` and every `RecordInterceptor` apply; the container transaction manager is used when dedup asks for one |

### What is refused at startup

- `ludwig.messaging.db.clusters` names a cluster not declared under `ludwig.kafka.clusters`.
- A consumer on a replaced cluster sets `retry.non-blocking=true` (retry topics need a broker).
- A listener on a replaced cluster subscribes by `topicPattern` (name the topics instead).
- A listener reads `default` through a factory `ListenerContainerFactoryBuilder` did not build, while
  `default` is replaced (without the builder there are no consumer settings to apply).
- A listener on a replaced cluster has no group id.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `ludwig.messaging.db.mode` | `active` | `active`: replaced clusters' writes go to the log. `draining`: writes go to each cluster's address and consumers read both. See [Moving to a real cluster](#moving-to-a-real-cluster) |
| `ludwig.messaging.db.clusters` | `[internal]` | Clusters the log stands in for, by their `ludwig.kafka.clusters` names (`default` = `spring.kafka.*`). Each must be declared |
| `ludwig.messaging.db.partitions` | `8` | Partitions per topic. **Fixed once records exist**: changing it moves keys between partitions and breaks per-key order across the change |
| `ludwig.messaging.db.poll-interval` | `1s` | How long an idle worker waits before polling again |
| `ludwig.messaging.db.retention` | `7d` | Records older than this are purged, **consumed or not** |
| `ludwig.messaging.db.purge-interval` | `1h` | How often the purge runs |
| `ludwig.messaging.db.schema` | `ludwig_messaging` | Schema of the tables. Same value in every service. **Fixed once deployed** (changeset checksums include it) |

Workers per listener follow the listener's `concurrency`, as Kafka consumer threads do.

## Deployment

- **Schema.** Liquibase changelog `db/changelog/messaging-db/messaging-db-changelog.xml`, applied by the
  starter on startup. Every service runs it with its own changelog table; each changeset is
  `IF NOT EXISTS` and takes an advisory lock first, so services may start in any order or concurrently.
- **Tables.** `message_log` (records), `partition_head` (next offset per partition; its row lock
  serialises writers so offsets never become visible out of order), `consumer_offset` (each group's
  position; its row lock is the partition assignment).
- **Connection pool.** A worker holds one pooled connection while handling a record and the listener's
  transaction uses another: add `concurrency × 2` per log listener to the pool size the service already
  needs.
- **Database load.** One indexed query per idle worker per poll interval. Throughput is that of one shared
  PostgreSQL: hundreds of records per second.

### Moving to a real cluster

1. **Give each replaced cluster its address** (`ludwig.kafka.clusters.<name>.bootstrap-servers` and its
   security settings) **and set `ludwig.messaging.db.mode=draining` on every service**, in any order.
   Writes now go to the cluster; consumers read **both** the log and the cluster, so nothing in the log
   is lost.
2. **Wait until `ludwig.messaging.db.lag` is 0 for every group.**
3. **Remove the dependency and the `ludwig.messaging.db.*` properties.** Listeners, factory beans and
   outbox routes stay as they are.
4. **Drop the schema** (Liquibase cannot; the changesets leave with the jar):

   ```sql
   DROP SCHEMA ludwig_messaging CASCADE;
   DELETE FROM databasechangelog WHERE author = 'ludwig-messaging-db';   -- in each service's changelog table
   ```

While a topic drains, one key can have records in both the log backlog and Kafka, and the two
containers interleave them: per-key order holds before and after the switch, not during it. Switch when
the topic is quiet if order matters. `mode` switches every replaced cluster at once, so give each its
address before switching.

## Observability

**Metrics:**

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `ludwig.messaging.db.lag` | multi-gauge | `group`, `topic` | Records a group has not consumed; refreshed every 30s so a scrape never queries the database |

Records consumed, retried and dead-lettered are counted by [messaging](../messaging-spring-boot-starter#observability)
(`ludwig.messaging.*`), as for a real cluster.

**Logs.**

| Level | Message | Meaning |
|---|---|---|
| `INFO` | `Reading <topics> from the log as group '<g>' with <n> worker(s)` | Listener attached to the log |
| `INFO` | `Purged <n> log record(s) produced before <t>` | Retention ran |
| `WARN` | `Purging <n> record(s) of topic '<t>' that group '<g>' never consumed` | **A consumer stopped or is too slow**: data is being deleted unread |
| `ERROR` | `Polling the log as group '<g>' failed; retrying` | Database problem for this worker |
| `ERROR` | `Skipping the record at ... dead-lettering is off` | Record dropped by configuration |

**Health.** No health indicator.

**Recommended alerts:**

| Condition | Why |
|---|---|
| `ludwig_messaging_db_lag` growing for > 15 min | Consumer stuck or too slow; records will be purged unread after `retention` |
| Log line `never consumed` | Unread records deleted |
| `ludwig_messaging_dropped_total` > 0 | See messaging runbook |

**Known gaps:** no metric for the log's write rate or table size, and no health indicator for the poll
workers. Watch `message_log` size with database monitoring.

## Audit

Not applicable on its own: dead-lettering is audited by messaging's recoverer
(`messaging.dead-lettered`, `messaging.dropped`). Appending to and purging the log are transport
operations, not state changes of business data.

## Administration

Operator endpoints are on the platform's administrative surface, `/admin/v1/{area}`, and exist only where `security-spring-boot-starter`'s surface does. Each is guarded by one operation; which of the service's roles hold it is the service's own `ludwig.admin.grants` (roles from the authority resolver, never token claims), and an operation granted to no role is refused to everyone. A `privileged` call needs an `Admin-Reason` header, and every call is audited under category `admin` with the operation id as its action. `GET /admin/v1/me/operations` lists what the caller holds.

| Operation | Endpoints | Allows |
|---|---|---|
| `messaging.read` | `GET /admin/v1/messaging/consumers` | See how far each consumer group is behind on each partition of the database log. |
| `messaging.operate` | none in this release | No operation yet; reserved so that grants for this area stay the same across releases. |
| `messaging.privileged` | none in this release | No operation yet; reserved so that grants for this area stay the same across releases. |

No endpoint moves an offset; any other method on `/admin/v1/messaging/consumers` is `405`.

Grant block (the role names are the service's own):

```yaml
ludwig:
  admin:
    grants:
      "[messaging.read]": [ORDERS_ADMIN, ORDERS_SUPPORT]
      "[messaging.operate]": [ORDERS_ADMIN]
      "[messaging.privileged]": [ORDERS_ADMIN]
```

## Runbook

| Symptom | Likely cause | What to do |
|---|---|---|
| Consumer receives nothing | Producer lacks the dependency (writes to the real cluster), or consumer's `cluster` not replaced | Check both services' dependencies and `ludwig.messaging.db.clusters`; `SELECT topic, count(*) FROM ludwig_messaging.message_log GROUP BY 1;` |
| `ludwig_messaging_db_lag` grows | Listener slow or failing | Check `ludwig_messaging_retries_total` and the service log; raise `concurrency` (≤ `partitions`) and the pool size accordingly |
| `never consumed` warning | Group stopped consuming longer than `retention` | The purged records are gone; ask the producer to republish if needed. Fix the consumer |
| Pool exhaustion / connection timeouts | Pool not sized for `concurrency × 2` per log listener | Increase `spring.datasource.hikari.maximum-pool-size` |
| Startup fails naming `topicPattern`, group id, non-blocking retry or an unknown cluster | One of the [refused configurations](#what-is-refused-at-startup) | Fix as the message says |
| Need to move to Kafka | - | [Moving to a real cluster](#moving-to-a-real-cluster) |

## Testing

`mvn verify` (Docker): `LogStoreIT`, `LogOnlyTransportIT`, `MixedTransportIT` (log and a real Kafka in one
service), `DrainingIT`, `SchemaInstallIT` (concurrent installs), plus `StartupValidationTest` for the
refusals and an `ArchitectureTest`.

## Design decisions

- **Attaches without editing other modules.** Writes go through messaging's `KafkaClusterProducers`
  seam, decorated; listeners through a `BeanPostProcessor` that wraps each container factory in a
  routing factory and builds the original Kafka container to reuse its listener, deserializers and
  transaction manager.
- **Removability is checked.** Architecture rule `kafka.nothing-depends-on-the-db-log-transport` fails
  any module that names a class of this one.
- **Unconsumed records are purged.** The database is shared; a stopped group must not grow it without
  bound. The warning is the signal.
- **Not a broker.** No compaction, no transactions spanning log and listener, no seeking, no
  `LISTEN/NOTIFY`, no retry topics, and a cluster is replaced whole (no per-topic setting).
