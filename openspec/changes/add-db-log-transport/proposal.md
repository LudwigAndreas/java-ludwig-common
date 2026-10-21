## Why

Services need to talk to each other asynchronously now, and there is no Kafka cluster they can write
to until next year. The services already read from external teams' Kafka clusters. They also already
produce through `outbox-spring-boot-starter` and consume through `@KafkaListener` and
`messaging-spring-boot-starter`. What is missing is somewhere to write. This change provides a
Kafka-shaped log in the shared PostgreSQL database. It is a temporary stand-in that can be removed
completely once a cluster exists. Removing it must not require editing any producer or listener.

## What Changes

- New starter `messaging-db-spring-boot-starter` (library, parented by the reactor root `common`):
  - **Which cluster it stands in for:** `ludwig.messaging.db.clusters`, default `[internal]`, naming
    clusters as `add-kafka-clusters` defines them. *Revised: the first version replaced the single
    `kafka` transport and chose per topic through `ludwig.messaging.db.consume`. With named clusters, a
    topic lives on a cluster, so the choice is per cluster and `consume` is removed.*
  - **Writes:** every record the platform would have produced to a replaced cluster - outbox writes
    over `kafka:<cluster>`, and dead letters addressed to it - is appended to a log table in the
    shared PostgreSQL database instead.
  - **Reads:** consumers whose cluster is replaced read from the log. Every other consumer reads from
    its own cluster. The consumer semantics are Kafka's:
    partitions, per-key ordering, an independent position per consumer group (fan-out), and
    at-least-once delivery.
  - **Consumer behaviour:** the same as the messaging starter's. Deserialization and the
    event-version gate, the correlation interceptor, dedup, blocking retry with backoff, then the
    dead-letter topic, with the same metrics and audit events.
  - **Cutover:** once the cluster has an address, `ludwig.messaging.db.mode=draining` sends its writes
    there while its consumers read from both the log and the cluster. The starter reports the remaining log lag per consumer
    group, so it is clear when it can be removed.
  - **Retention:** a purge job.
- No change to any listener, listener container factory bean or outbox route. **Depends on
  `add-kafka-clusters`**, whose `KafkaClusterProducers` bean is the producer seam the starter replaces
  for its clusters. The first version attached through the `kafkaOutboxDispatcher` bean name and a
  dead-letter handler swap, and those are retired.
- A new ArchUnit rule in `architecture-rules`: no class outside the new starter's package may depend
  on it. That rule is what keeps the starter removable.

## Non-goals

- Writing to external teams' Kafka topics. Nothing on this side writes there today, so there is no
  per-topic write override.
- A separate bootstrap address for the future in-house cluster. It only matters in `draining` mode,
  which only exists once that cluster exists. It is recorded as an open question.
- Non-blocking retry topics on log-backed topics. They are refused at startup.
- `topicPattern` listeners touching log-backed topics. They are refused at startup.
- Push delivery (`LISTEN/NOTIFY`). Polling at about 1s is accepted.
- Exactly-once semantics, transactions spanning the log and a listener's own writes, and compaction.
- Long-term operation. This is a stand-in scheduled for removal.

## Capabilities

### New Capabilities

- `db-log-transport`: a Kafka-shaped log in PostgreSQL that the outbox writes to and `@KafkaListener`
  consumers read from, chosen per consumed topic, with a draining cutover and complete removability.

### Modified Capabilities

None. The `messaging-envelope` requirements are Kafka-transport requirements and stay as they are.
The new capability restates the consumer guarantees the log transport keeps.

Shared contracts in `openspec/specs/` touched: **none modified**. The design checks the new module
against `module-dependency-direction`, `repository-layout`, `pom-topology`, `database-migration` (from
the in-progress `unify-sql-migrations` change) and `enforcement-triad`, and it conforms to all of them.

## Impact

| Module | POM tier | Change | In-repo dependents (gate) |
|---|---|---|---|
| `messaging-db-spring-boot-starter` | library (parent `common`) | **new** | none |
| `architecture-rules` | library (role `rules`, parent `common`) | new rule: nothing outside the starter depends on its package. The existing `KafkaRules` are expected to pass unchanged, because the starter declares no `DeadLetterPublishingRecoverer` and no method returning `ConcurrentKafkaListenerContainerFactory` | its 11 dependents (`scripts/manifest.sh module build/architecture-rules`) |
| `ludwig-bom` | bom | manages the new artifact's version | every module (imports it) |
| root `pom.xml` (`common`) | parent | adds `<module>` | — |

`project-index.json` is currently stale (POM-set SHA mismatch) and must be regenerated with
`scripts/manifest.sh build` after the POM changes.

Runtime: three tables in the shared PostgreSQL database, polling load of one query per second per
(consumer group, partition) a service owns, and `ludwig.messaging.db.*` properties. No new third-party
dependency. Spring Kafka, Liquibase and the JDBC stack are already in the BOM.
