## Why

A service reads from several external teams' Kafka clusters and writes to some of them. Its own
in-house cluster arrives next year and is stood in for by the DB log until then. Everything in the
platform resolves Kafka through one address, `spring.kafka.*`. The consumer factory builder, the
dead-letter producer, the outbox's Kafka template and dispatcher all use it. So a service can reach only
one cluster, and the DB-log starter's cutover writes to whichever cluster that one address is. The
module README says outright that this is still to be decided.

## What Changes

- **Named clusters.** `ludwig.kafka.clusters.<name>.*` has the shape of Spring Boot's `spring.kafka.*`
  and is bound on top of it, so a cluster entry states only what differs (address, security, client
  id). `spring.kafka.*` itself stays the cluster named `default`, so a service that configures no
  cluster changes nothing.
- **Consumers.** Each consumer reads from one cluster, chosen by
  `ludwig.messaging.consumers.<name>.cluster`, with `default` as the default. A listener reads from one
  cluster. Reading two clusters takes two listeners.
- **Dead letters.** `ludwig.messaging.consumers.<name>.dead-letter.cluster` is `source` (the default,
  today's behaviour) or any cluster name. It is settable for all consumers under
  `ludwig.messaging.defaults.*`.
- **Producers.** The outbox registers one Kafka dispatcher per cluster, under transport
  `kafka:<cluster>`. Plain `kafka` stays the `default` cluster, so existing routes are unchanged.
- **Dedup is cluster-scoped.** The idempotency claim scope for a consumer on a non-default cluster
  includes the cluster. Without this, two clusters with the same topic name would share claims, and a
  record with no idempotency key would be discarded as a "duplicate" of the record at the same
  partition and offset on the other cluster. Claims on the default cluster keep their current scope,
  so no stored claim changes meaning.
- **Metrics.** `ludwig.messaging.*` meters gain a `cluster` tag. This is a binary change to the
  `MessagingMetrics` interface, recorded as an API-baseline exception.
- **One producer seam.** Every producer the platform builds - outbox templates and dead-letter
  templates - comes from one replaceable `KafkaClusterProducers` bean. A new architecture rule keeps it
  the only place.
- **Outbox records carry the publisher's correlation id.** *Added at the user's request.* Today no
  outbox-produced record carries `X-Correlation-Id` on any cluster. The poller thread has no
  correlation context, and audit-core's `CorrelationProvider` seam has only a no-op implementation,
  because observability never published one. Two fixes: observability publishes a `CorrelationProvider`
  over its `CorrelationContext`, and the outbox captures the id at publish time, in the request thread,
  into the message's headers under messaging's correlation header name. This also gives audit events
  their correlation id.
- **Refused at startup:** a consumer naming an undefined cluster, a route transport `kafka:<name>`
  naming an undefined cluster, and non-blocking retry topics on a non-default cluster (out of scope,
  see Non-goals).

## Non-goals

- Fan-out of one outbox event to several clusters. Each event resolves to one route, as today.
- Non-blocking retry topics on a non-default cluster. They stay on `default` and are refused elsewhere.
- One listener reading from two clusters. A spring-kafka container connects to one cluster, and the
  platform does not hide that.
- Cluster discovery, failover between clusters, or mirroring.
- Backward compatibility. *Scope decision by the user:* this ships as a new major release, so
  signatures change where the design needs them to (`MessagingMetrics`, `DeadLetterTemplates`,
  `ResolvedConsumerSettings`, `OutboxDispatcherRegistry`), with no shims and no API-baseline exceptions.
  Each breaking change is listed under **BREAKING** in the CHANGELOG.
- Changing the DB-log starter. That is a follow-up revision of `add-db-log-transport`, which then
  replaces a named cluster rather than "the" Kafka transport. It is listed here because it is the
  reason the cluster model has to exist first.

## Capabilities

### New Capabilities

- `kafka-clusters`: named Kafka clusters, which cluster a consumer reads from and dead-letters to, and
  which cluster an outbox route writes to, with dedup and metrics kept apart per cluster.

### Modified Capabilities

None. The `messaging-envelope` and `idempotency-claim` requirements hold unchanged. The first is about
headers and per-record handling, the second about how a claim is taken, and neither says which cluster.
The new capability adds the cluster dimension on top.

Shared contracts in `openspec/specs/` touched: **none modified**. The design checks the change against
`messaging-envelope`, `idempotency-claim`, `module-dependency-direction`, `starter-conditional-wiring`
and `enforcement-triad`.

## Impact

| Module | POM tier | Change | In-repo dependents (gate) |
|---|---|---|---|
| `messaging-spring-boot-starter` | library (parent `common`) | cluster properties and registry; builder, dead-letter producer and metrics per cluster | identity-projection, messaging-db, notification-service, outbox, user-settings |
| `outbox-spring-boot-starter` | library | one Kafka dispatcher per cluster; startup validation of `kafka:<name>` routes | audit, crud-service-example, export, file-action, file-ingest, messaging-db, notification-service, user-settings |
| `idempotency-spring-boot-starter` | library | cluster-scoped claim scope for the Kafka record filter | file-action, grpc-server, messaging, notification-service |
| `observability-spring-boot-starter` | library | publishes a `CorrelationProvider` over `CorrelationContext`, ordered before audit-core's no-op | crud-service-example, export, file-action, grpc-client, grpc-server, notification-service, reconciliation, rest-client, user-settings |
| `architecture-rules` | library (role `rules`) | new rule: no `DefaultKafkaProducerFactory` constructed outside `ru.ludwigandreas.messaging..` | its 11 dependents |

No new third-party dependency. No schema change: the cluster is carried in the outbox's existing
`transport` column (`VARCHAR(50)`), which bounds a cluster name to 44 characters.
