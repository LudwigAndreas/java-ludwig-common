## Context

See proposal.md, "Why". Every place that reaches Kafka reads Boot's single `KafkaProperties` bean today:

- `ListenerContainerFactoryBuilder.consumerFactory` uses `kafkaProperties.buildConsumerProperties`.
- `DeadLetterTemplates.create(kafkaProperties, …)` builds one dead-letter producer, once, in the
  builder's constructor.
- `OutboxKafkaAutoConfiguration` builds `outboxKafkaTemplate` from `kafkaProperties.buildProducerProperties`
  and registers one `kafkaOutboxDispatcher` (transport `KAFKA`). `OutboxDispatcherRegistry` builds a map
  keyed by upper-cased transport with `toUnmodifiableMap`, so two dispatchers for one transport fail
  startup.
- `IdempotentRecordFilterStrategy` claims under the scope `IdempotencyScopes.forTopic(record.topic())`,
  and falls back to the key `topic:partition:offset` when a record has no idempotency key.
- `MicrometerMessagingMetrics` tags by `topic` only.

The builder's public signature (`forJsonPayload(name, type, topics…)`, `forTextPayload(name, topics…)`)
is what three production consumers call. It must not change.

## Goals / Non-Goals

**Goals:**

- A consumer's cluster is configuration, keyed by the consumer name it already has. No factory `@Bean`
  method, listener or route changes for a service that stays on one cluster.
- One seam per direction that a stand-in can replace: a producer factory per cluster. This is what
  `add-db-log-transport` will build on.

**Non-Goals:**

- A cluster abstraction for anything except consumption, dead-lettering and the outbox. A service's
  own direct `KafkaTemplate` use stays on Boot's `spring.kafka.*`.

## Decisions

### D1. Clusters are bound as Boot `KafkaProperties`, layered on spring.kafka

`KafkaClusters` (messaging, package `settings`) holds one `KafkaProperties` per cluster name. `default`
is Boot's bean itself. A named cluster is built by binding `spring.kafka` into a fresh
`KafkaProperties`, then binding `ludwig.kafka.clusters.<name>` onto the same instance
(`Binder.bind(name, Bindable.ofInstance(…))`). Inheritance therefore follows Boot's own property
names: consumer, producer, security, ssl, properties.

- **Alternative, a narrow `{bootstrap-servers, security}` record:** rejected. Partner clusters differ
  in SASL mechanism, client id and SSL stores, and every field a narrow record left out would become
  its own feature request.
- **Alternative, a full separate copy with no inheritance:** rejected. Every cluster would repeat
  serializers and group defaults, and the copies drift.

`default` is reserved. Declaring `ludwig.kafka.clusters.default` fails startup, because `spring.kafka`
already is that cluster.

### D2. The seam is a producer factory per cluster

A new bean, `KafkaClusterProducers` in messaging, has one method:
`ProducerFactory<K, V> create(String cluster, Serializer<K>, Serializer<V>)`. The default
implementation is a `DefaultKafkaProducerFactory` over the cluster's `buildProducerProperties`. It is
`@ConditionalOnMissingBean`, and it is the **only** place a platform producer is constructed:

- the outbox's `outboxKafkaTemplate` (`default`) and its per-cluster templates
- the dead-letter templates. `DeadLetterTemplates.create` takes the producer factory instead of
  building one. Its `DelegatingByTypeSerializer` decision stays where it is.

`add-db-log-transport` then replaces one bean, and a cluster it stands in for gets log-appending
producers for outbox writes and dead letters alike. That is simpler than its current bean-name override
of `kafkaOutboxDispatcher`.

- **Alternative, a dispatcher-level seam:** rejected. It would not cover dead letters, which are the
  other half of what writes to a cluster.

### D3. The builder resolves the consumer's cluster from its settings

`ConsumerSettings` gains `cluster` and `dead-letter.cluster`. `ResolvedConsumerSettings` gains
`cluster()` (default `default`) and `deadLetterCluster()` (default `source`, resolved to `cluster()`).
The builder:

- builds the consumer factory from `KafkaClusters.properties(settings.cluster())`
- takes the dead-letter template for `settings.deadLetterCluster()` from a map built lazily, one
  template per cluster, replacing the single eager `deadLetterTemplate` field. The rationale for
  keeping it out of the context, written in the constructor, still holds.
- passes the cluster to the metrics and to the dedup filter (D5)

No signature changes. A consumer's cluster is set where its other overrides already live, under its
name.

### D4. The outbox registers one dispatcher per cluster, through a group bean

`OutboxDispatcherRegistry` gains a second constructor input: `List<OutboxDispatcherGroup>`, where
`OutboxDispatcherGroup` is a small interface returning dispatchers. The flattened set is still collected
with `toUnmodifiableMap`, so a duplicate transport still fails startup. `OutboxKafkaAutoConfiguration`
keeps `kafkaOutboxDispatcher` (transport `KAFKA`, cluster `default`) under the same name and guard, and
adds one group bean whose dispatchers are `KAFKA:<cluster>` for every declared cluster.

- **Alternative, one bean per cluster from an `ImportBeanDefinitionRegistrar`:** rejected. Beans
  depending on bound configuration at registration time is the fragile ordering Boot steers away from,
  and the replacement seam is D2 anyway.

Startup validation: every `ludwig.outbox.routes.*.transport` (and the default route) that starts with
`kafka:` must name a declared cluster, or startup fails, naming the route.

### D5. Cluster-scoped dedup, default cluster unchanged

`IdempotencyScopes` gains `forTopic(String cluster, String topic)`. It returns the existing
`forTopic(topic)` for `default`, and `<cluster>/<topic>` otherwise. `IdempotentRecordFilterStrategy` gains
`forCluster(String cluster)`, which returns a strategy bound to that cluster. The builder calls it when
the filter is an `IdempotentRecordFilterStrategy` and the consumer's cluster is not `default`. A
service-supplied filter of another type is used as is, and the README says it must scope by cluster
itself.

- **Dependency direction:** messaging → idempotency already exists, and it is optional. The builder
  checks the type with `ClassUtils.isPresent`, as `MessagingDedupAutoConfiguration` already does.
- **Alternative, a header stamped by a per-container interceptor:** rejected. It mutates consumed
  records to carry configuration.

### D6. Metrics

`MessagingMetrics` methods gain a `cluster` argument. This is an interface in messaging, implemented
only by `MicrometerMessagingMetrics` and `NoopMessagingMetrics`, so the change is internal.
`AuditingDeadLetterRecoverer` passes the cluster it was built for. This is a breaking change to a public
interface of messaging, allowed because this ships as a major release. It is listed in the CHANGELOG.

### D8. The correlation id is captured at publish time

*Added at the user's request.* The outbox dispatches on its poller thread, where no request context
exists, so a correlation id can only be the one captured when the event was published, in the
request's thread. `DefaultOutboxEventPublisher` takes `CorrelationProvider` (audit-core, already an
outbox dependency) and, when the event's headers do not already carry the correlation header, adds it.
The header name is messaging's `ludwig.messaging.correlation-header` (default `X-Correlation-Id`), the
name `EnvelopeReader` reads. The stored headers then travel through `KafkaOutboxDispatcher` unchanged,
on every cluster and through the DB log alike.

Today `CorrelationProvider` has only audit-core's no-op implementation, so observability publishes
one over `CorrelationContext`, guarded by `@ConditionalOnMissingBean` and ordered before
`AuditCoreAutoConfiguration` so the no-op backs off. Observability already depends on audit-core.

- **Alternative, stamping on the producer:** rejected. The producer runs on the poller thread, after
  the context is gone.

### D7. Retry topics stay on default

`MessagingConfigurationValidator` refuses `nonBlocking=true` with a non-`default` cluster. Retry-topic
configuration is a context-wide `RetryTopicConfiguration` over one template, and making it per-cluster
is out of scope.

### Dependency-direction check

No new in-repo edge is added. The edges used already exist: messaging → idempotency (optional), and
outbox → messaging. `KafkaClusters` and `KafkaClusterProducers` live in messaging because outbox
depends on messaging and not the other way round. Does messaging already depend on outbox, directly or
transitively? `scripts/manifest.sh module sources/outbox-spring-boot-starter` lists outbox's dependents
as audit, crud-service-example, export, file-action, file-ingest, messaging-db, notification-service and
user-settings. messaging is not among them, so there is no cycle. idempotency's change uses nothing from
messaging.

### POM changes

None. No module gains a dependency, and no third-party version changes.

### Conventions and their checks

- **"A platform producer is built only by `KafkaClusterProducers`"**: an ArchUnit rule in
  `architecture-rules` (`KafkaRules`). No class outside `ru.ludwigandreas.messaging..` constructs a
  `DefaultKafkaProducerFactory`. The rule is the same shape as `no-private-dead-letter-recoverer`.
  Without it, the next producer added bypasses the stand-in seam silently.
- **"A listener reads from one cluster"** needs no check. spring-kafka enforces it, because a container
  has one consumer factory.

## Risks / Trade-offs

- **[A service-supplied dedup filter is not cluster-aware]** → documented in the idempotency and
  messaging READMEs. The built-in filter is handled.
- **[Breaking signatures]** → accepted for this major release, and listed in the CHANGELOG.
- **[Cluster names bounded by `outbox_message.transport VARCHAR(50)`]** → validated at startup: a
  cluster name longer than 44 characters is refused.
- **[One more context of `KafkaProperties` per cluster]** → created once, at startup. No per-record
  cost.

## Migration Plan

Nothing to migrate for a service that declares no cluster. To adopt: declare clusters, set
`consumers.<name>.cluster` and `dead-letter.cluster`, and change routes to `kafka:<cluster>`. Rollback
removes those properties. Dedup claims on `default` keep their scope, so a rollback loses none.

## Open Questions

None that change the specs or the task list.
