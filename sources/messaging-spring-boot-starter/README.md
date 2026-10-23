# messaging-spring-boot-starter

***English** · [Русский](README.ru.md)*

The consumer side of the platform's Kafka envelope. It builds every `@KafkaListener` container factory
from one set of decisions - safe deserialization, backoff then a dead-letter topic, per-record
acknowledgement, payload version gating, consumer-side dedup, lag and silence metrics, several Kafka
clusters - and owns the wire header names that [`outbox-spring-boot-starter`](../outbox-spring-boot-starter)
writes. A service declares its listener, topic and payload type; everything else comes from here.

| | |
|---|---|
| Role | starter |
| Depends on (in-repo) | [`audit-core`](../audit-core); optional: [`idempotency-spring-boot-starter`](../idempotency-spring-boot-starter), [`web-core-spring-boot-starter`](../web-core-spring-boot-starter) |
| Used by | `outbox`, `asyncapi`, `identity-projection`, `messaging-db`, `user-settings` starters; `notification-service` |
| Surfaces | Kafka (inbound; outbound to dead-letter topics) |

Packages:

```
ru.ludwigandreas.messaging
├── api             MessageHeaders, InboundEnvelope, EnvelopeReader, DeadLetterTopics
├── settings        MessagingProperties, per-consumer resolution, KafkaClusters
├── config          auto-configurations
├── consumer        ListenerContainerFactoryBuilder - the one set of decisions
├── serialization   safe JSON deserializer and the version gate
├── dlt             dead-letter producer and the audited recoverer
├── audit           DeadLetterAudit
├── metrics         counters and the silence gauge
└── error           the two failures this module raises
```

## Usage

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>messaging-spring-boot-starter</artifactId>
</dependency>
```

Ask the builder for a factory at the payload's exact type and register it under your own bean name:

```java
@Bean(CONTAINER_FACTORY)
ConcurrentKafkaListenerContainerFactory<String, OrderCreated> orderListenerContainerFactory(
        ListenerContainerFactoryBuilder builder, OrderProperties properties) {
    return builder.forJsonPayload("orders-ingress", OrderCreated.class, properties.getTopic());
}

@KafkaListener(topics = "${orders.topic}", containerFactory = CONTAINER_FACTORY)
void onOrderCreated(OrderCreated event, @Header(MessageHeaders.EVENT_TYPE) String type) { ... }
```

- `forTextPayload(name, topics...)` builds a `String`-payload factory for a listener that dispatches on
  `ludwig-event-type` across several payload types on one topic.
- The first argument is the **consumer name**: the key its overrides live under in
  `ludwig.messaging.consumers.*`. It is not the bean name, so renaming a bean never silently changes
  configuration.
- The factory is constructed at the consumer's own type; nothing shared is looked up or cast.
- **A listener must not declare an `Acknowledgment` parameter.** The default ack mode is `RECORD`, where
  Spring supplies no `Acknowledgment`; the parameter is resolved as the payload instead and every record
  fails with `Payload value must not be empty`, then is dead-lettered.

`InboundEnvelope` / `EnvelopeReader` read the envelope headers from a record.

### The envelope

| Header | Carries |
|---|---|
| `ludwig-event-type` | the kind of event; what a consumer dispatches on |
| `ludwig-event-version` | the payload schema version - see [Payload schema versions](#payload-schema-versions) |
| `ludwig-aggregate-type` | the aggregate type the event is about |
| `ludwig-aggregate-id` | the aggregate instance |
| `ludwig-idempotency-key` | the producer's dedup key, stable across republishes and redeliveries |
| `ludwig-trace-id` | the W3C trace id, when the producer's trace was sampled |
| `ludwig-produced-at` | when the producer emitted it, ISO-8601 UTC (not the broker timestamp) |
| `X-Correlation-Id` | the correlation id |

`X-Correlation-Id` keeps the name [`observability-spring-boot-starter`](../observability-spring-boot-starter)
uses on HTTP and Kafka, so one id has one name everywhere. It is configurable there and
`EnvelopeReader` honours the override. Only these names are read; a record without
`ludwig-event-type` has an unknown type.

### Error handling

Every factory the builder makes has:

- **`ErrorHandlingDeserializer` around key and value.** A malformed payload becomes a record the error
  handler can classify and dead-letter instead of an endless poison-pill loop.
- **Auto-commit off.** The container commits after the listener returns. A database outage therefore
  never advances the offset past records that were not processed.
- **`DefaultErrorHandler` with exponential backoff**: 1s × 3, capped at 30s, **4 deliveries including the
  first**, overridable per consumer. The backoff runs in the container, so the partition pauses and a
  retried record stays in order with the records behind it.
- **Non-retryable `DeserializationException` and `MessageConversionException`**: a payload that cannot
  be parsed goes to the dead-letter topic on its first attempt.
- **`DeadLetterPublishingRecoverer` to `<topic>.dlt`, partition `-1`**: the broker chooses the partition,
  so the dead-letter topic does not need the source's partition count.
- **A dead-letter producer that can send raw bytes** (`DeadLetterTemplates`, `DelegatingByTypeSerializer`),
  because a record that failed deserialization is republished as its original `byte[]`.

Every dead-letter send is counted and audited. A dead-letter send that itself failed is counted and
audited separately (`dropped`): the record is gone.

### Acknowledgement

`RECORD`: a record is committed when its listener returns, and its failure affects no other record in
the poll. `MANUAL` is available for a consumer that genuinely batches, and only by setting
`ludwig.messaging.consumers.<name>.ack-mode` explicitly.

### Consumer-side dedup

The dedup store is [`idempotency-spring-boot-starter`](../idempotency-spring-boot-starter). This module
attaches any `RecordFilterStrategy` in the context to every factory it builds, so a deployment that sets
`ludwig.idempotency.kafka.enabled=true` has dedup on every consumer, keyed on `ludwig-idempotency-key`.

- Opt a consumer out with `ludwig.messaging.consumers.<name>.dedup=false`. A projection that converges
  on a value should: it is already correct on replay, and a claim adds a table write and a failure mode.
- **The claim needs the listener's transaction.** A `TRANSACTIONAL` claim on a container with no
  transaction manager commits outside the listener's work, so a rolled-back record stays claimed and its
  redelivery is discarded as a duplicate. The module attaches the application's transaction manager when
  `ludwig.idempotency.kafka.mode=TRANSACTIONAL`, and refuses to start if
  `ludwig.messaging.dedup.container-transaction=false` is set against that mode.

### Payload schema versions

A consumer declares the versions it understands:

```yaml
ludwig:
  messaging:
    consumers:
      orders-ingress:
        accepted-versions: { min: 1, max: 1 }
```

`VersionGatingDeserializer` reads `ludwig-event-version` before parsing and refuses an out-of-range
version with a non-retryable `DeserializationException`, so the record goes to the dead-letter topic on
its first attempt. A version 2 payload is never mapped onto a version 1 type. Unbounded is the default.

To route unknown versions to their own topic, set `dead-letter.suffix` for that consumer.

### Non-blocking retry topics

Per-topic opt-in, and refused for a consumer that relies on ordering: non-blocking retries republish the
failed record, so it is applied **after** records produced later than it, which breaks the per-key order
the producer's `orderingKey` promises.

```yaml
ludwig:
  messaging:
    retry-topics.enabled: true          # enables @EnableKafkaRetryTopic for the application
    consumers:
      search-index-feed:
        ordered: false                  # required, otherwise startup fails
        topics: [ search.index.feed ]   # required, otherwise the configuration applies to no topic
        retry.non-blocking: true
```

- `ludwig.messaging.defaults.retry.non-blocking` is refused: giving up order is a per-topic decision.
- The module supplies a single-threaded `RetryTopicSchedulerWrapper` when the application has none, so
  `@EnableKafkaRetryTopic` starts without a second `TaskScheduler` bean that would capture the
  application's `@Scheduled` methods.
- Retry topics stay on the `default` cluster.

### Kafka clusters

A service can read from and dead-letter to several clusters. `spring.kafka.*` is the cluster named
`default`. A named cluster has `spring.kafka.*`'s shape, is bound on top of it, and states only what
differs:

```yaml
ludwig:
  kafka:
    clusters:
      partner-a: { bootstrap-servers: a.partner:9093, security: { protocol: SSL } }
      internal:  { bootstrap-servers: our-kafka:9092 }
  messaging:
    consumers:
      orders-ingress:
        cluster: partner-a                 # where it reads from
        dead-letter: { cluster: internal } # where its dead letters go; default `source`
```

- **One cluster per consumer.** Reading two clusters takes two listeners. The cluster is configuration
  keyed by consumer name; listener code does not change.
- **Dead letters** go to the cluster the record came from (`source`) unless `dead-letter.cluster` names
  another. `ludwig.messaging.defaults.dead-letter.cluster` sets it for all consumers.
- **Dedup is per cluster.** The claim scope is `kafka:<cluster>/<topic>`; `default` uses `kafka:<topic>`.
  A custom `RecordFilterStrategy` must scope by cluster itself.
- **Metrics** carry a `cluster` tag.
- **Producers.** Every producer the platform builds (dead-letter templates, outbox templates) comes from
  the `KafkaClusterProducers` bean. The architecture rule `kafka.producers-come-from-the-cluster-seam`
  keeps it the only place.
- Cluster names: lower-case letters, digits and dashes, at most 44 characters (so the outbox transport
  `kafka:<name>` fits its column). `default` is reserved. A consumer naming an undeclared cluster fails
  startup.

`ListenerContainerFactoryBuilder.consumerNameOf(factory)` returns the consumer name of a factory;
[`asyncapi-spring-boot-starter`](../asyncapi-spring-boot-starter) uses it to document each listener with
its cluster and dead-letter channel.

### Security default

`setUseTypeHeaders(false)` on every JSON deserializer, with **no property to turn it on**. With type
headers, the producer chooses which class Jackson instantiates on the consumer - a deserialization gadget.
A consumer with several payload types on one topic dispatches on `ludwig-event-type` to types it already
knows.

## Configuration

Every key under `ludwig.messaging.consumers.<name>` also exists under `ludwig.messaging.defaults`
(applies to all consumers). A per-consumer block overrides only what it names.

| Property | Default | Meaning |
|---|---|---|
| `ludwig.messaging.enabled` | `true` | Master switch (also requires `@KafkaListener` on the classpath) |
| `ludwig.kafka.clusters.<name>.*` | none | A named cluster; `spring.kafka.*`'s shape, bound on top of it |
| `ludwig.messaging.correlation-header` | `X-Correlation-Id` | Must match `ludwig.observability.correlation.kafka.header-name` |
| `ludwig.messaging.metrics.enabled` | `true` | Module metrics and the Kafka client metrics (lag) |
| `ludwig.messaging.dedup.enabled` | `true` | Attach a `RecordFilterStrategy` from the context, if one exists |
| `ludwig.messaging.dedup.container-transaction` | from the claim mode | `false` with `TRANSACTIONAL` claims is refused |
| `ludwig.messaging.retry-topics.enabled` | `false` | Enable non-blocking retry infrastructure application-wide |
| `…consumers.<name>.ack-mode` | `RECORD` | `MANUAL` for a consumer that genuinely batches |
| `…consumers.<name>.concurrency` | the factory's | Listener threads |
| `…consumers.<name>.ordered` | `true` | `false` is required for non-blocking retries |
| `…consumers.<name>.dedup` | `true` | `false` for a converging projection |
| `…consumers.<name>.silence-threshold` | none | No silence gauge until set |
| `…consumers.<name>.retry.initial-backoff` | `1s` | |
| `…consumers.<name>.retry.multiplier` | `3.0` | |
| `…consumers.<name>.retry.max-backoff` | `30s` | |
| `…consumers.<name>.retry.max-attempts` | `4` | **Deliveries including the first** |
| `…consumers.<name>.retry.non-blocking` | `false` | See [Non-blocking retry topics](#non-blocking-retry-topics) |
| `…consumers.<name>.dead-letter.enabled` | `true` | `false` = log and drop |
| `…consumers.<name>.dead-letter.suffix` | `.dlt` | |
| `…consumers.<name>.dead-letter.cluster` | `source` | `source` or a declared cluster |
| `…consumers.<name>.cluster` | `default` | A cluster declared under `ludwig.kafka.clusters` |
| `…consumers.<name>.accepted-versions.min` / `.max` | `1` / unbounded | A bound installs the version gate |
| `…consumers.<name>.topics` | empty | Needed only for the non-blocking retry opt-in |

## Deployment

**Dead-letter topics must exist before the service ships.** A production cluster usually has
`auto.create.topics.enable=false`; a missing dead-letter topic makes the first poison record fail to
dead-letter, its offset is committed, and the record is lost (`ludwig.messaging.dropped` > 0).

| Source topic | Dead-letter topic |
|---|---|
| notification ingress (`ludwig.notification.ingress.topic`) | `<topic>.dlt` |
| `ludwig.user-settings.projection.topic` (default `user.settings`) | `user.settings.dlt` |
| `ludwig.identity.kafka.topic` (default `oidc.users`) | `oidc.users.dlt` |
| any other consumer topic | `<topic><dead-letter.suffix>` |

Partition count and replication need not match the source (the recoverer sends to partition `-1`).
Give the dead-letter topic **longer retention** than the source: a person reads it, possibly after a
weekend.

Credentials for named clusters go into `ludwig.kafka.clusters.<name>.*` from the secret store, never
into the image.

## Observability

**Metrics:**

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `kafka.consumer.fetch.manager.records.lag` | gauge | client tags (topic, partition, group) | Consumer lag, from the Kafka client (`MicrometerConsumerListener`, bound on every factory) |
| `ludwig.messaging.consumed` | counter | `cluster`, `topic` | Records handed to a listener |
| `ludwig.messaging.retries` | counter | `cluster`, `topic` | Retry attempts |
| `ludwig.messaging.dead.lettered` | counter | `cluster`, `topic`, `dlt`, `exception` | Records sent to a dead-letter topic |
| `ludwig.messaging.dropped` | counter | `cluster`, `topic` | Dead-letter sends that failed - the record is lost |
| `ludwig.messaging.silent` | gauge | `cluster`, `topic` | `1` when nothing arrived within `silence-threshold` |

`silent` catches what lag cannot: a topic with no traffic, a dead container and a mistyped group id all
show lag zero. The clock starts at **startup**, so a consumer that never receives anything is reported.
The gauge is evaluated on scrape, not pushed by a scheduler that could itself stop.

**Logs.** `WARN` per dead-lettered record (coordinates, attempts, exception); `ERROR` when dead-lettering
failed; `WARN` at startup for dedup without a transaction manager and for `retry-topics.enabled` with no
non-blocking consumer. Payloads are never logged.

**Traces.** The correlation interceptor from `observability-spring-boot-starter` is composed into every
factory, so the correlation id reaches the MDC of every listener thread.

**Health.** No health indicator; consumer state is visible through lag and `silent`.

**Recommended alerts:**

| Condition | Why |
|---|---|
| `increase(ludwig_messaging_dropped_total[5m]) > 0` | Records are being lost; usually a missing dead-letter topic. Page |
| `increase(ludwig_messaging_dead_lettered_total[15m]) > 0` | Records were not processed and need a person |
| `ludwig_messaging_silent == 1` | A consumer has received nothing for longer than its threshold |
| `kafka_consumer_fetch_manager_records_lag` above the topic's tolerance for > 10 min | Consumer falling behind |

## Audit

| Field | Dead-lettered | Dropped |
|---|---|---|
| category | `messaging` | `messaging` |
| action | `messaging.dead-lettered` | `messaging.dropped` |
| actor | `system` | `system` |
| resource | the record's coordinates, event type | same |
| outcome | `FAILURE` (record not processed) | `FAILURE` |
| attributes | topic, partition, offset, eventType, eventVersion, aggregateType, aggregateId, deadLetterTopic, exception, attempts | same, `deadLetterTopic` null |

Correlation and trace ids are copied from the envelope, so the event joins the producer's trail. The
payload is never included.

## Runbook

| Symptom | Likely cause | What to do |
|---|---|---|
| `ludwig_messaging_dropped_total` > 0, `ERROR Failed to dead-letter the record` | Dead-letter topic missing, or no write ACL on it | Create `<topic>.dlt` (or grant ACL). The dropped records are gone from this consumer's view: re-read them from the source topic by resetting the group offset to the coordinates in the audit event / log, or ask the producer to republish |
| `ludwig_messaging_dead_lettered_total` grows | Poison payload, unsupported version, or listener bug | Read the `exception` tag and the dead-letter topic. Fix the cause, then replay the `.dlt` records to the source topic (e.g. `kafka-console-consumer` piped to `kafka-console-producer` with headers, or a replay endpoint) |
| Every record on a topic dead-lettered with `Payload value must not be empty` | Listener declares an `Acknowledgment` parameter | Remove the parameter |
| `ludwig_messaging_silent == 1` | No traffic, dead container, or wrong group id / topic | Check the producer's metrics first; then `kafka-consumer-groups --describe --group <group>` - no assigned partitions means wrong topic or group |
| Lag grows steadily | Listener slower than inflow, or blocked on retries of one record | Look at `ludwig_messaging_retries_total`; raise `concurrency` (≤ partitions) or fix the slow dependency |
| Startup fails: `dedup.container-transaction=false with ... TRANSACTIONAL` | Contradictory dedup settings | Remove the override or switch the claim mode |
| Startup fails: non-blocking retry on an ordered consumer | `retry.non-blocking=true` without `ordered=false` | Decide whether the topic may lose order; set `ordered: false` only if it may |
| Startup fails: unknown cluster | Consumer names a cluster not under `ludwig.kafka.clusters` | Declare it or fix the name |

## Testing

- Unit tests for settings resolution, version gating and the builder.
- Integration tests against a real Kafka (`SharedConsumerIT`, `MultiClusterConsumerIT`; Testcontainers,
  needs Docker): poison records dead-lettered immediately, bounded redelivery, no silent drop, dedup,
  order preserved across a retry, unknown versions refused, producer-supplied types ignored, silence
  reported, per-cluster consumption and dead-lettering.
- The module runs the full `architecture-rules` set against itself.
- In a consuming service: test the listener with `@LudwigKafkaTest` from
  [`test-support`](../test-support), including one malformed record.

## Design decisions

- **One factory builder, enforced.** Architecture rules `kafka.container-factories-set-an-error-handler`
  (every factory sets an error handler or comes from the builder) and
  `kafka.no-private-dead-letter-recoverer`; Checkstyle `SecondDeadLetterSuffix` forbids a second `.dlt`
  constant.
- **`outbox` depends on this module**, for the header names. The reverse would be a reactor cycle. To keep
  the outbox's transitive dependencies small, spring-kafka, web-core, the validation API and the
  idempotency starter are optional here.
- **No dedup store here.** The idempotency starter is the store; this module only attaches it.
- **No unbounded default version gate.** A bound invented by the platform would dead-letter live traffic.
- **Three meanings of "idempotency" stay separate**: outbox publish dedup, reconciliation's partner key
  type, and the idempotency starter's work dedup are different mechanisms.
- **web-core is optional.** Nothing here writes an HTTP response; the bundle and problem mapper are for a
  service that re-raises a messaging error from its own surface.
