# messaging-spring-boot-starter

[Русская версия](README.ru.md)

The consumer half of the envelope `outbox-spring-boot-starter` produces.

`outbox-spring-boot-starter` owns production properly. Consumption was wired three times in this
repository, and no two wirings agreed on anything that mattered — two of them had a defect that loses
records. This module is one set of consumer decisions, taken once, that a service asks for by name.

- [What was wrong](#what-was-wrong)
- [What this module is](#what-this-module-is)
- [The envelope](#the-envelope)
- [Adopting it](#adopting-it)
- [The error-handling convention](#the-error-handling-convention)
- [Acknowledgement](#acknowledgement)
- [Consumer-side dedup](#consumer-side-dedup)
- [Payload schema versions](#payload-schema-versions)
- [Security defaults that must not regress](#security-defaults-that-must-not-regress)
- [Observability](#observability)
- [Non-blocking retry topics](#non-blocking-retry-topics)
- [Configuration reference](#configuration-reference)
- [**Deployment prerequisite: the dead-letter topics**](#deployment-prerequisite-the-dead-letter-topics)
- [The rules that stop this recurring](#the-rules-that-stop-this-recurring)
- [What this module deliberately does not do](#what-this-module-deliberately-does-not-do)

## What was wrong

| | notification `NotificationMessagingConfig` | user-settings `UserSettingsKafkaAutoConfiguration` | identity-projection `IdentityKafkaAutoConfiguration` |
|---|---|---|---|
| container factory | own, typed | own, `ConsumerFactory<?,?>` wildcarded **and cast** | **none** — fell through to Boot's default |
| error handler | `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` | **none** | **none** (Boot's default) |
| backoff | `ExponentialBackOff(1s, ×3, cap 30s)` | — | — |
| non-retryable | `DeserializationException`, `MessageConversionException` → straight to DLT | — | — |
| ack mode | `RECORD` | `MANUAL` | Boot default (`BATCH`) |
| deserializer | `ErrorHandlingDeserializer` wrapping `JsonDeserializer`, `setUseTypeHeaders(false)` | Boot defaults, payload as `String`, parsed by hand | same |
| dead-letter topic | `<topic>.dlt`, partition `-1` | — | — |

Three ack modes for one semantic is its own bug: `RECORD`, `MANUAL` and `BATCH` give three different
answers to "what happens to the other records in this poll when one of them fails".

The two failure modes were different and both lose data:

- **user-settings**: `AckMode.MANUAL` with no error handler. A listener that threw before acknowledging
  never acknowledged, so the record was redelivered — forever, with no backoff, at full speed. A
  projection that could not apply a change because a constraint fired did not fall behind; it stopped,
  hot, and stayed stopped.
- **identity-projection**: Boot's default `DefaultErrorHandler` retries ten times with no backoff and
  then **logs and moves on**. The record was dropped, and the only trace was a log line in a service
  nobody tails.

Two further defects were found while building this module, both invisible until a test with a real
broker looked for them. Neither is hypothetical; both were live.

- **A poison record could not be dead-lettered at all.** `DeadLetterPublishingRecoverer` republishes the
  original `byte[]` when the failure was a deserialization failure — which is correct, because bytes
  that could not be parsed are the only faithful representation of that record. The application's
  `KafkaTemplate` is configured with a `StringSerializer` or a `JsonSerializer` and cannot send them:
  the publication fails with `SerializationException: Can't convert value of class [B`, the offset is
  committed anyway, and the record is gone. In the one case the dead-letter topic exists for. Fixed by
  `DeadLetterTemplates`, which gives the recoverer a producer with a `DelegatingByTypeSerializer`.
- **The attempt budget was one higher than its comment said.** Spring's `ExponentialBackOff.maxAttempts`
  counts the *intervals* it hands out, which is the number of retries; the first delivery has no
  interval before it. `setMaxAttempts(4)` therefore produces five deliveries.
  `NotificationMessagingConfig` set 4 under a comment reading "Attempts before a record is
  dead-lettered, including the first". Here `max-attempts` means what that comment said, and an
  integration test counts the deliveries.

## What this module is

One `ConcurrentKafkaListenerContainerFactory` builder, plus the wire-header contract both halves share.
Nothing else. It declares no listener, no topic and no payload type — those are the consuming service's,
and they are the only things a consumer still has to say.

```
ru.ludwigandreas.messaging
├── api             MessageHeaders, InboundEnvelope, EnvelopeReader, DeadLetterTopics
├── settings        MessagingProperties and the per-consumer resolution
├── config          the autoconfigurations
├── consumer        ListenerContainerFactoryBuilder — the one set of decisions
├── serialization   the safe JSON deserializer and the version gate
├── dlt             the dead-letter producer and the audited recoverer
├── audit           DeadLetterAudit
├── metrics         the counters, and the silence signal
└── error           the two failures this module raises
```

`outbox-spring-boot-starter` **depends on this module**, for `api`'s header names. That direction is the
only one that works: this module needs a `KafkaTemplate` to dead-letter with, not an outbox, and a mutual
dependency would be a reactor cycle regardless of scope — Maven's reactor DAG ignores scope. Every
non-optional dependency here therefore becomes one of every outbox consumer, which is why spring-kafka,
web-core, the validation API and the idempotency starter are all optional. The fallback, if that edge
ever has to reverse, is recorded in the POM: split a zero-dependency `messaging-core` out of `api`.

## The envelope

`OutboxEvent` has carried `aggregateType`, `aggregateId`, `eventType`, `eventVersion`, `idempotencyKey`
and `traceId` since it was written, and `KafkaOutboxDispatcher` put some of them on the record — but the
names lived in the dispatcher as string literals, so there was no way for a consumer to read them back
except by copying a literal. One consumer did exactly that, with a comment saying the two had to agree.
The other two gave up and parsed the payload, which is why `eventVersion` reached production with
nothing consuming it.

| Header | Carries |
|---|---|
| `ludwig-event-type` | the kind of event; what a consumer dispatches on |
| `ludwig-event-version` | the payload's schema version — see [Payload schema versions](#payload-schema-versions) |
| `ludwig-aggregate-type` | the type of aggregate the event is about |
| `ludwig-aggregate-id` | the aggregate instance |
| `ludwig-idempotency-key` | the producer's dedup key, across republishes as well as redeliveries |
| `ludwig-trace-id` | the W3C trace id, when the producer's trace was sampled |
| `ludwig-produced-at` | when the producer emitted it, ISO-8601 UTC — **not** the broker's timestamp |
| `X-Correlation-Id` | the correlation id |

`X-Correlation-Id` breaks the prefix pattern on purpose. It is the name
`observability-spring-boot-starter` already reads and writes on both HTTP requests and Kafka records, and
it is configurable there. An id that travelled as one name over HTTP and another over the broker would
be two ids as far as every log query is concerned — which is the two-homes problem this module exists to
remove, not to add. It is listed here so a consumer has one place to look, and `EnvelopeReader` accepts
the configured override.

### The legacy names, and when they can go

`KafkaOutboxDispatcher` wrote `event-type`, `event-version`, `idempotency-key` and `trace-id` before this
module existed, and there are records on real topics carrying them — a topic's retention outlives a
release. So **the dispatcher writes both spellings** and `EnvelopeReader` reads the canonical one first,
falling back to the legacy one.

- The legacy **write** can be dropped once every topic's retention window has passed with the new
  producer deployed.
- The legacy **read** can be dropped once no consumer group can still be asked to replay from before
  that point — which is the later of the two.

Dropping either earlier means a consumer reading `null` where a value exists. For `ludwig-event-type`
that means a record silently ignored as an unknown type: not an error anywhere, and the replica simply
never learns about that change.

## Adopting it

A consumer asks the builder for a factory at the exact type its payload is, and registers it under its
own bean name:

```java
@Bean(CONTAINER_FACTORY)
ConcurrentKafkaListenerContainerFactory<String, OrderCreated> orderListenerContainerFactory(
        ListenerContainerFactoryBuilder builder, OrderProperties properties) {
    return builder.forJsonPayload("orders-ingress", OrderCreated.class, properties.getTopic());
}
```

`forTextPayload(name, topics...)` is the same thing for a consumer that parses its own JSON — the right
shape for a listener that dispatches on `ludwig-event-type` across several payload types on one topic,
which a factory fixed to a single type cannot express.

The first argument is the **consumer name**, which is the key its overrides live under in
`ludwig.messaging.consumers.*` and the tag its metrics carry. It is deliberately not the bean name: a
bean rename should not be a silent configuration change.

### Why construct rather than inject

`UserSettingsKafkaAutoConfiguration` used to take a `ConsumerFactory<?, ?>` and cast it, with a comment
explaining that asking for `ConsumerFactory<String, String>` "looks tidier and does not work" because a
service declaring its own differently-typed factory leaves no bean matching that signature. The
diagnosis was right and the cast was the wrong remedy — it removed the compiler's check rather than
satisfying it. Here nothing of a shared signature is looked up: the factory is *constructed* at the
consumer's own type, so there is nothing to collide with and nothing to cast.

### A listener must not declare an `Acknowledgment`

The default ack mode is `RECORD`, and Spring Kafka supplies an `Acknowledgment` only in a manual ack
mode. A listener that declares one anyway has that parameter resolved as its **payload**, and every
record then fails with `Payload value must not be empty` — which, with an error handler behind it, means
every record on the topic is dead-lettered. Both migrated projections had to drop the parameter.

Nothing about the commit behaviour changes: a record that reaches the end of the listener is committed by
the container, and one whose handling threw propagates out and leaves the offset uncommitted.

## The error-handling convention

`NotificationMessagingConfig` was the only one of the three that had been reasoned about, so its
decisions are the platform defaults and its reasoning moved into the code that now makes them.

- **`ErrorHandlingDeserializer` wrapping both key and value.** A malformed payload becomes a record the
  error handler can classify and dead-letter. Without it the exception is thrown before the listener is
  reached, the container has nothing to recover, and it retries the same unparseable bytes forever — the
  classic poison-pill stall.
- **Auto-commit off, unconditionally.** Offsets are committed by the container after the listener
  returns, never by the broker on a timer. With auto-commit, a database outage advances the offset past
  every record that arrived during it and those records are gone — the failure that looks like "the
  notifications from Tuesday afternoon never arrived" and has no trace anywhere.
- **`DefaultErrorHandler` with `ExponentialBackOff`**, defaulting to 1s × 3 capped at 30s, four
  deliveries including the first, overridable per consumer.
- **`DeserializationException` and `MessageConversionException` registered non-retryable.** *A payload
  that could not be deserialized will never deserialize, so it goes straight to the dead-letter topic
  instead of spending four attempts proving it.*
- **`DeadLetterPublishingRecoverer` to `<topic>.dlt`, partition `-1`.** `-1` lets the broker choose:
  preserving the source partition would require the dead-letter topic to have at least as many
  partitions, which nothing enforces — and a send to a partition that does not exist **fails silently**.
- The backoff is in the *container*, so a failing record does not occupy a thread while it waits, and the
  whole partition pauses — which is what keeps a retried record in order relative to the ones behind it.

Every dead-letter send is counted (`ludwig.messaging.dead.lettered`) and recorded as an `audit-core`
event. A send that *failed* is counted separately (`ludwig.messaging.dropped`) with its own audit action,
because it is materially worse: the record is gone and the platform believes it has a safety net it does
not have.

## Acknowledgement

Standardised on `RECORD`. With at-least-once delivery and dedup available in front, per-record
acknowledgement gives the smallest redelivery window and the simplest reasoning: a record is committed
when its listener returned, and nothing else in the poll is affected by its failure.

`MANUAL` stays available for a consumer that genuinely batches, and taking it requires setting
`ludwig.messaging.consumers.<name>.ack-mode` — so nobody inherits it by accident, which is how
user-settings ended up with manual acknowledgement and no error handler.

## Consumer-side dedup

**The inbox already exists.** `idempotency-spring-boot-starter` ships
`IdempotentRecordFilterStrategy`, `IdempotencyScopes`, `IdempotencyStore` with
`ClaimMode.TRANSACTIONAL`, the claim table, the TTL and `IdempotencyPurgeJob`. A shared inbox with dedup
and retention is done, and building a second one here would recreate in one commit precisely the
two-homes problem this series of consolidations has been removing.

What was missing was never a store. It was that adopting the store took a line in every service's own
container factory, so a service got dedup by remembering rather than by configuration. This module
attaches any `RecordFilterStrategy` in the context to every factory it builds, by default — so a
deployment that sets `ludwig.idempotency.kafka.enabled=true` has dedup on every consumer.

A per-consumer opt-out is `ludwig.messaging.consumers.<name>.dedup=false`, and a projection that
converges on a value should use it: it is already correct on a replay, and a claim in front of it adds a
table, a write and a failure mode for a guarantee it already had.

### The transaction the claim needs

A `ClaimMode.TRANSACTIONAL` claim on a container with no transaction manager commits **outside** the
listener's transaction, so the key stays reserved for work that rolled back — and Kafka's redelivery of
that record, certain because the offset was not committed either, is discarded as a duplicate. The
message is never delivered and nothing says so.

So `MessagingDedupAutoConfiguration` reads `ludwig.idempotency.kafka.mode` and attaches the
application's transaction manager when it is `TRANSACTIONAL`. Setting
`ludwig.messaging.dedup.container-transaction=false` against a transactional claim mode is **refused at
startup**: it is not a trade-off a deployment can make.

## Payload schema versions

`ludwig-event-version` was produced from the start and consumed by nothing. That is not a missing
nicety. A payload evolving under a consumer that ignores its version is a silent data-corruption path:
Jackson maps the bytes onto the consumer's *current* type, a field that was renamed or split arrives as
its Java default, the listener writes a row, and nothing records that a version 2 event was applied by a
version 1 consumer. The row looks like an ordinary row.

A consumer declares its bound and gets a refusal:

```yaml
ludwig:
  messaging:
    consumers:
      orders-ingress:
        accepted-versions: { min: 1, max: 1 }
```

`VersionGatingDeserializer` reads the header inside `Deserializer.deserialize(topic, headers, data)` —
the one place in the pipeline that can read the version and decide **not** to parse. A `RecordInterceptor`
runs after the container has deserialized the value, so by then the mapping has already happened. The
refusal surfaces as a `DeserializationException`, which is non-retryable, so the record reaches the
dead-letter topic on its first attempt. Correct: a version does not change on redelivery.

Unbounded is the default, and deliberately: a bound this module invented would dead-letter live traffic
on the deploy that introduced it.

This class rejects; it does not route. Routing an unknown version to a topic of its own is the
dead-letter topic with a different name, and a consumer that wants one sets
`dead-letter.suffix`. What must not happen, and what this makes impossible, is deserializing it anyway.

## Security defaults that must not regress

`setUseTypeHeaders(false)`, on every JSON deserializer this module builds, with **no property that turns
it back on**.

> The type is fixed by this consumer rather than taken from a record header: a header-driven
> deserializer will instantiate whatever class a producer names, which is a deserialization gadget
> waiting to happen.

Concretely: with type headers on, the class Jackson instantiates is chosen by whoever produced the
record. Anything that can write to the topic picks a class on the consumer's classpath and gets it
constructed with attacker-chosen field values. The classpath of a Spring Boot service is large and the
gadget chains against Jackson polymorphic typing are a documented, recurring CVE family. "We trust our
topics" is the assumption that fails, because a topic is not an authenticated caller.

user-settings and identity-projection sidestepped this by taking `String` payloads and parsing by hand.
Moving them onto a shared typed factory must not hand them the gadget as the price of consistency, which
is why the setting is applied once, where neither can forget it. A consumer that genuinely needs several
payload types on one topic dispatches on `ludwig-event-type` — a value *it* maps to a type it already
knows, rather than a class name the producer chose.

## Observability

`MicrometerConsumerListener` is bound on every consumer factory this module builds, which publishes the
Kafka client's own metrics — `kafka.consumer.fetch.manager.records.lag` among them. **Nothing in this
platform bound it before**, so a platform with an observability starter had no consumer lag metric at
all, which is the number every Kafka runbook starts with.

| Metric | Meaning |
|---|---|
| `kafka.consumer.fetch.manager.records.lag` | lag per topic, partition and group, from the client |
| `ludwig.messaging.consumed{topic}` | records handed to a listener |
| `ludwig.messaging.retries{topic}` | retry attempts |
| `ludwig.messaging.dead.lettered{topic,dlt,exception}` | **alert on any** |
| `ludwig.messaging.dropped{topic}` | a dead-letter send that failed — should be zero at all times |
| `ludwig.messaging.silent{topic}` | 1 when nothing has arrived within the configured window |

### Silence

`ludwig.messaging.silent` is the signal lag cannot provide. A topic with no traffic, a consumer whose
container died, and a consumer whose group id was mistyped so it was never assigned a partition all read
as lag zero. Only a clock tells them apart, and only if somebody says how long an absence is tolerable:

```yaml
ludwig.messaging.consumers.orders-ingress.silence-threshold: 15m
```

Null by default, because a threshold is a statement about a topic's traffic that only the team owning it
can make, and a wrong one is worse than none. This is the same failure as file-ingest's file that never
arrived, and it presents the same way: a healthy pod, an empty log, and a projection quietly going stale.

The clock is armed at **startup**, not at the first record — a consumer that never receives anything is
the case that matters most, and it would never report silence otherwise. The gauge is a supplier
evaluated on scrape rather than a value pushed by a scheduler: a scheduler that has to keep running for
the gauge to stay truthful is one more thing that can stop without saying so, which is the exact failure
this gauge reports.

The correlation interceptor `observability-spring-boot-starter` contributes is **composed** into every
factory built here rather than replaced. Boot applies it only to the factory Boot itself
auto-configures, so `NotificationMessagingConfig`'s own factory dropped the correlation id for every
record it consumed — latently, because the listener's code was correct and nothing had connected the two.

## Non-blocking retry topics

Shipped, per-topic opt-in, and refused at startup for a consumer that relies on ordering.

A blocking `DefaultErrorHandler` pauses the partition while it retries. That is head-of-line blocking,
and it is what preserves order. Non-blocking retry topics keep the partition moving by republishing the
failed record, so the record is re-applied **after** records that were produced later than it. The
producer side publishes an `orderingKey` on every event, which is a promise that same-key events are
applied in order. Switching a topic to non-blocking retries breaks that promise silently, and the symptom
is a value that was corrected and reverts hours later — the hardest class of bug to attribute, because by
the time anybody looks the retry topic is empty and the logs have rolled.

The opt-in is two explicit steps and one declaration:

```yaml
ludwig:
  messaging:
    retry-topics.enabled: true          # enables @EnableKafkaRetryTopic for the application
    consumers:
      search-index-feed:
        ordered: false                  # without this, startup fails
        topics: [ search.index.feed ]   # without these, the configuration applies to no topic
        retry.non-blocking: true
```

The module-level switch exists in addition to the per-consumer flag because `@EnableKafkaRetryTopic`
changes how *every* annotated listener in the application is bootstrapped, which is not something a
per-consumer property should be able to do to a service that was not expecting it. `topics` is required
because a `RetryTopicConfiguration` including no topics applies to nothing — the opt-in appears to have
worked while the blocking handler keeps doing the retrying.

`ludwig.messaging.defaults.retry.non-blocking` is refused outright: giving up ordering is a decision
about one topic's semantics.

Retry topics are *timed* — a record goes to a retry topic and its consumer is paused until the backoff has
elapsed — so `@EnableKafkaRetryTopic` needs a scheduler. Without one the context fails with `Either a
RetryTopicSchedulerWrapper or TaskScheduler bean is required`, a message that names neither this module nor
the property that caused it. This module supplies a single-threaded `RetryTopicSchedulerWrapper` when the
application has neither. A wrapper rather than a bare `TaskScheduler` bean on purpose: a bare one would be
a second `TaskScheduler` in the context, and Spring's `@Scheduled` infrastructure resolves that by type —
so a module publishing one would silently move every scheduled method in the application onto a thread pool
this module sized.

## Configuration reference

Every key under `ludwig.messaging.consumers.<name>` also exists under `ludwig.messaging.defaults`, which
applies to every consumer. A per-consumer block overrides only what it names — every field is nullable
precisely so that it can.

| Key | Default | Notes |
|---|---|---|
| `enabled` | `true` | also gated on `KafkaListener` being on the classpath |
| `correlation-header` | `X-Correlation-Id` | must match `ludwig.observability.correlation.kafka.header-name` |
| `metrics.enabled` | `true` | includes the Kafka client metrics, lag among them |
| `dedup.enabled` | `true` | attaches a `RecordFilterStrategy` from the context, if there is one |
| `dedup.container-transaction` | from the claim mode | `false` against `TRANSACTIONAL` is refused |
| `retry-topics.enabled` | `false` | enables the non-blocking infrastructure application-wide |
| `…<name>.ack-mode` | `RECORD` | `MANUAL` for a consumer that genuinely batches |
| `…<name>.concurrency` | the factory's own | |
| `…<name>.ordered` | `true` | `false` is the precondition for non-blocking retries |
| `…<name>.dedup` | `true` | `false` for a projection that converges |
| `…<name>.silence-threshold` | none | no gauge until it is set |
| `…<name>.retry.initial-backoff` | `1s` | |
| `…<name>.retry.multiplier` | `3.0` | |
| `…<name>.retry.max-backoff` | `30s` | |
| `…<name>.retry.max-attempts` | `4` | **deliveries, including the first** |
| `…<name>.retry.non-blocking` | `false` | see above |
| `…<name>.dead-letter.enabled` | `true` | `false` means logged and dropped |
| `…<name>.dead-letter.suffix` | `.dlt` | |
| `…<name>.accepted-versions.min` / `.max` | `1` / unbounded | a bound installs the version gate |
| `…<name>.topics` | empty | only needed for a non-blocking retry opt-in |

## Deployment prerequisite: the dead-letter topics

**This is a deployment prerequisite, not a footnote. Provision the dead-letter topics before this
ships.**

Two of the three migrated consumers had no dead-letter topic at all and now have one. The topics are:

| Source topic | Dead-letter topic | New with this change |
|---|---|---|
| the notification ingress topic (`ludwig.notification.ingress.topic`) | `<that>.dlt` | no — already existed |
| `ludwig.user-settings.projection.topic`, default `user.settings` | `user.settings.dlt` | **yes** |
| `ludwig.identity.kafka.topic`, default `oidc.users` | `oidc.users.dlt` | **yes** |

A dead-letter topic that auto-creates in dev and does not exist in production is a silent drop wearing a
different hat. Kafka's `auto.create.topics.enable` is usually true in a development cluster and usually
false in a production one, which is precisely the configuration that makes this pass every test and fail
on the first poison record in production — the publication fails, the offset is committed, and
`ludwig.messaging.dropped` becomes non-zero for a record nobody can now find.

Partition count and replication do not have to match the source topic — the recoverer sends to partition
`-1` and lets the broker choose, exactly so that they need not. Retention should be *longer* than the
source topic's: the point of the topic is that a human reads it, and a human reads it after the weekend.

Alert on `ludwig.messaging.dead.lettered` being non-zero, and on `ludwig.messaging.dropped` being
non-zero at all — the second means the recoverer itself is failing, and the most likely reason is a topic
that was never provisioned.

## The rules that stop this recurring

Both defects this module removes were defects of **omission**, which is the kind a review does not catch
because there is nothing on the screen to object to. Fixing the code fixes the state of the repository;
only a rule stops the same reasonable local decision being made again next quarter.

`architecture-rules`' `kafka` group gains two:

- **`kafka.container-factories-set-an-error-handler`** — every method returning a
  `ConcurrentKafkaListenerContainerFactory` either calls `setCommonErrorHandler` or obtains the factory
  from `ListenerContainerFactoryBuilder`. This is the check that makes the user-settings and
  identity-projection defects impossible to reintroduce. Its boundaries are stated on the rule: it cannot
  see a factory assembled through a helper method, and it cannot see a factory Boot auto-configures.
- **`kafka.no-private-dead-letter-recoverer`** — nothing outside `ru.ludwigandreas.messaging..`
  constructs a `DeadLetterPublishingRecoverer`.

`checkstyle-rules` gains **`SecondDeadLetterSuffix`**, which fails the build on a second dead-letter
suffix constant. The split is deliberate and is the one this repository already draws for the redaction
mask: ArchUnit reads compiled bytecode, where a `static final String`'s *value* is a constant-pool entry
`JavaField` does not expose, so "no field whose value is `.dlt`" is not expressible there.

This module runs the whole rule set against itself, and it found two real problems that were fixed rather
than suppressed: a package cycle between `config` and `consumer` (the settings moved to their own
package, the same resolution notification-service reached), and `MessagingProperties` not being
`@Validated`.

## What this module deliberately does not do

- **It does not ship a dedup store.** See [Consumer-side dedup](#consumer-side-dedup).
- **It does not unify the platform's three uses of the word "idempotency".** `OutboxProperties$Idempotency`
  is dedup on the outbox's own row at publish time; reconciliation's `IdempotencyKey` is a value type for
  a partner-facing key; `idempotency-spring-boot-starter` is the work-dedup store. Those are three
  legitimately different mechanisms, not scattered copies of one.
- **It takes no web-core dependency it can avoid.** Nothing here reaches an HTTP response: an unsupported
  event version is raised inside a deserializer on a listener thread and its destination is a topic. The
  i18n bundle and the contributed `ExceptionProblemMapper` exist for the service that chooses to re-raise
  one of these from its own surface — a replay endpoint re-feeding a dead-lettered record — and web-core
  is optional so that `outbox-spring-boot-starter` does not inherit it.
- **It does not move `OidcUserEventListener` onto the typed envelope.** That change is worth making and
  is deliberately separate: it alters what the listener does with a malformed payload, and bundling a
  behaviour change into the fix for a silent drop would make the fix unreviewable.
