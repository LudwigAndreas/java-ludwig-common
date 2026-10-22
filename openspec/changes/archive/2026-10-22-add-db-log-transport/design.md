## Context

See proposal.md, "Why". The current wiring, in the terms that constrain the approach:

- **Producing.** The outbox poller hands each claimed row to the `OutboxDispatcher` whose
  `transport()` matches the row's transport. `OutboxDispatcherRegistry` collects dispatchers into a
  map with `toUnmodifiableMap`, so **two dispatchers for one transport fail startup**. The Kafka one
  is the bean `kafkaOutboxDispatcher`, guarded by `@ConditionalOnMissingBean(name =
  "kafkaOutboxDispatcher")` in `OutboxKafkaAutoConfiguration`.
- **Consuming.** Listeners are `@KafkaListener` methods. Their `containerFactory` beans are built by
  `ListenerContainerFactoryBuilder` (`forJsonPayload` / `forTextPayload`), which sets the consumer
  factory (with the version-gating deserializer), the `CommonErrorHandler` (backoff, then the
  audited dead-letter recoverer), the `RecordInterceptor` (correlation) and the `RecordFilterStrategy`
  (dedup) **on the factory**. The three factory `@Bean` methods return the concrete
  `ConcurrentKafkaListenerContainerFactory<…>`.
- **Database.** One PostgreSQL instance is shared by every service. Schemas are installed by one
  `SpringLiquibase` bean per module (see `OutboxLiquibaseAutoConfiguration`), using Liquibase
  formatted SQL per `unify-sql-migrations`.

## Goals / Non-Goals

**Goals:**

- Zero edits outside the new starter. That includes the services, `messaging-spring-boot-starter`
  and `outbox-spring-boot-starter`, apart from the BOM, the reactor `<module>` and one ArchUnit rule.
- Consumer guarantees equal to the Kafka path's: retry and dead letter, the version gate, dedup and
  correlation all come from the same objects the builder already made, not from re-implementations
  where reuse is possible.

**Non-Goals:**

- Throughput beyond what inter-service traffic needs on one shared PostgreSQL. The target is
  hundreds of records per second, not Kafka's numbers.
- A general-purpose broker. Anything Kafka offers that the platform's consumers do not use
  (compaction, transactions, seeking, headers-only records) is absent.

## Decisions

### D1. Attach from outside, by bean name and post-processing, and change no existing module

- **Producer side.** The starter registers its own bean named `kafkaOutboxDispatcher` from an
  autoconfiguration ordered `@AutoConfigureBefore(OutboxKafkaAutoConfiguration)`. The outbox's guard
  then skips its own bean, leaving exactly one `kafka` dispatcher and so no registry clash.
  - In `active` mode, the starter's bean appends to the log.
  - In `draining` mode, the bean is a plain `KafkaOutboxDispatcher` constructed over the
    `outboxKafkaTemplate`. Its constructor is public.
- **Consumer side.** A `BeanPostProcessor` wraps every `KafkaListenerContainerFactory` bean in a
  `RoutingListenerContainerFactory`. Spring Kafka resolves `containerFactory = "…"` with
  `getBean(name, KafkaListenerContainerFactory.class)`, so the replacement's type need not be the
  declared `ConcurrentKafkaListenerContainerFactory`. The `@Bean` methods keep their signatures.

Alternatives considered:

- **A `MessageHandler` seam in messaging, with listeners migrated to it.** Rejected: it edits every
  listener, and the user wants removal to be one dependency.
- **Widening the three `@Bean` return types.** Unnecessary, given the name-based lookup.
- **Subclassing `ConcurrentMessageListenerContainer` and overriding `doStart`.** Rejected: it couples
  to Spring Kafka internals.

### D2. One composite container per endpoint, split by topic

`RoutingListenerContainerFactory.createListenerContainer(endpoint)` partitions the endpoint's topics
into *log* topics (listed in `consume`) and *Kafka* topics.

- **Kafka topics.** The original factory creates a container for an endpoint narrowed to just these
  topics. The narrowing is a delegating `KafkaListenerEndpoint` that overrides `getTopics()`.
- **Log topics.** A `DbLogListenerContainer` is created.
- **Draining.** In `draining`, the log topics go to **both** containers.
- **Result.** A `CompositeListenerContainer` starts, stops and reports both containers. When only
  one side is non-empty, that one container is returned bare.

**How the log container gets the builder's behaviour.** It asks the original factory to build a
container for the log topics, **never starts it**, and takes from it:

- the fully adapted `MessageListener`, which already carries the endpoint's argument resolution and
  the dedup filter
- the `RecordInterceptor`
- the value and key `Deserializer` from the factory's `ConsumerFactory`
- the consumer's `ResolvedConsumerSettings`, which give it the backoff, the attempt budget and the
  non-retryable classes

Whether every one of these is reachable through public API is **the spike (tasks group 1)**. The
fallback for any that is not is to read the setting from the `ResolvedConsumerSettings` and the
platform beans the builder itself used. That moves the wiring into the starter but not the decision.

### D3. The log schema: offsets assigned under a per-partition lock

```
message_log      (topic, partition, "offset") PK; record_key, headers jsonb, payload bytea, produced_at
                 index (produced_at) for the purge
partition_head   (topic, partition) PK; next_offset
consumer_offset  (group_id, topic, partition) PK; committed_offset, attempts, next_attempt_at,
                 last_error, owner, heartbeat_at
```

- **Append.** Inside the outbox's own dispatch transaction, the starter runs `UPDATE partition_head
  SET next_offset = next_offset + 1 … RETURNING`, then inserts the row. The head row lock serialises
  writers per partition, so offsets become visible in order, which satisfies the gap requirement.
  Alternatives:
  - A `bigserial` offset is rejected: its gaps cause skipped records.
  - Filtering by snapshot (`pg_snapshot_xmin`) is rejected: it is correct but harder to reason about,
    and contention here is just the outbox pollers.
- **Partitions.** `murmur2(key) % ludwig.messaging.db.partitions`, which is Kafka's default
  partitioner. A record without a key goes to partition 0. Partition rows for a topic are created
  on its first append.
- **Payload as `bytea`.** The configured `Deserializer` consumes the bytes unchanged, and poison
  records keep their bytes.

### D4. Partition assignment by `SKIP LOCKED`, one record per claim

*Revised during implementation: the first draft claimed a partition per round and handled up to a
batch of records under one transaction. That is replaced by one record per claim, which needs no
`batch-size` or `round-timeout` setting and costs one redelivery on a crash instead of a batch.*

Each worker (one per unit of the listener's `concurrency`) runs this loop, and waits
`ludwig.messaging.db.poll-interval` (default 1s) whenever an iteration finds nothing to do:

1. On container start, ensure `consumer_offset` rows exist for this group and each subscribed
   (topic, partition), using `INSERT … ON CONFLICT DO NOTHING`. A new row's starting position comes
   from the consumer's `auto-offset-reset`: the current head for `latest`, -1 for `earliest`.
2. On a pooled connection of its own, claim **one** partition with work: `SELECT … FOR UPDATE OF o SKIP
   LOCKED LIMIT 1`, where `next_attempt_at <= now()` and the head is past the position. Ordering by
   `next_attempt_at`, which success moves to `now()`, gives the least recently served partition first.
3. Read the next record after `committed_offset`. Deserialize it with the factory's deserializers,
   check spring-kafka's deserialization-exception headers, then run the interceptor and the listener,
   inside the container's transaction manager when dedup set one.
4. Apply the outcome and commit, which releases the claim:
   - **Success:** move the position past the record.
   - **Failure with attempts remaining:** increment `attempts` and set `next_attempt_at` from the
     backoff. The partition waits without a thread sleeping, which matches "backoff lives in the
     container" from `messaging-envelope`.
   - **Budget spent, or a non-retryable failure:** append to `<topic>.dlt` through messaging's audited
     recoverer, then move past the record.
   - **The dead-letter append failed:** keep the position and hold the partition for the longest
     backoff.

The claim's connection is **never bound to the thread**. The listener opens its own transactions
through the application's transaction manager, and `JpaTransactionManager` refuses to start on a
thread that already has a JDBC connection bound for its data source. The listener's writes are
therefore in their own transactions, exactly as under a Kafka container, where the offset commit is
not part of the listener's transaction either. A crashed instance's claim transaction rolls back, and
its partition is free on the next poll of any instance. Delivery is at least once, and the redelivery
after a crash is what dedup exists for.

Alternatives:

- Advisory locks are rejected: they are invisible in tables and leak on pooled connections.
- Lease rows with a heartbeat are rejected: they add a reclaimer, which the transaction-scoped lock
  makes unnecessary.
- A batch per claim was the first draft, and is rejected for the reasons in the note above.

### D5. Dead letters and metrics reuse the platform's objects

- **Dead-letter writes.** These go through the same append path, wrapped in messaging's
  `AuditingDeadLetterRecoverer`. In `active` mode, a record read from Kafka also dead-letters into the
  log: the Kafka container's `CommonErrorHandler` is replaced, through the public
  `setCommonErrorHandler`, by a `DefaultErrorHandler` built from the same resolved settings. The
  backoff arithmetic is the builder's, and a comment ties the two together. **No
  `DeadLetterPublishingRecoverer` is declared**, so `KafkaRules.no-private-dead-letter-recoverer`
  holds. In `draining` mode, log-read dead letters go to Kafka through messaging's
  `DeadLetterTemplates`, and Kafka-read ones keep messaging's own handler.
- **The consumer name.** Settings are resolved by consumer name, and a factory does not carry it. The
  same post-processor proxies `ListenerContainerFactoryBuilder`, recording, for each factory it returns,
  the name it was asked for.
- **Interceptors.** A container's `RecordInterceptor` is not reachable through public API (the getter
  is protected), so the log container composes every `RecordInterceptor` bean in the context. Those
  are the beans the builder composes. This is D2's named fallback.
- **The outbox's write path.** The outbox's own `KafkaOutboxDispatcher` is constructed over a
  `KafkaTemplate` whose producer factory returns a `MockProducer` subclass that appends to the log.
  Header building stays in one place.
- **Metrics and audit.** Counting goes through the existing `MessagingMetrics`, and auditing through
  `DeadLetterAudit`. This is not a second audit path.

### D6. Schema installation is idempotent across services

The starter ships its own `SpringLiquibase` bean, `messagingDbLiquibase`, in the same pattern as
`OutboxLiquibaseAutoConfiguration`.

- **Schema.** Tables live in `ludwig.messaging.db.schema` (default `ludwig_messaging`), so that
  services with schema-per-service still meet in one place.
- **Changesets.** *Revised during implementation:* there is no precondition. Every statement is
  `IF NOT EXISTS`, and each changeset first takes `pg_advisory_xact_lock(hashtext('ludwig-messaging-db'))`.
  That covers a service whose migration history has never seen the changeset, and two services racing.
  The rollback is `NOT REQUIRED` on purpose: one service's rollback must not drop the log every other
  service uses.
- **The schema name is fixed once deployed.** Liquibase checksums the SQL with the parameter
  substituted, so a changed `ludwig.messaging.db.schema` fails validation against a changelog table
  that recorded the old value. This was found by the test suite, where three contexts with different
  schemas shared one changelog table.

### D7. Startup validation

At context refresh, the starter fails startup with the consumer and topic named when:

- a consumer with `non-blocking=true` reads a log topic
- an endpoint uses `topicPattern` while `consume` is non-empty and the pattern matches any listed
  topic

### D8. Purge

The purge job runs on the starter's scheduler. `job-core` is already used by outbox for the same
self-scheduling pattern. Each run:

1. counts, per (group, topic), the rows older than `retention` that lie beyond that group's
   committed position
2. logs that count
3. deletes the old rows in batches

The default `retention` is 7 days. Purging unconsumed records is a deliberate policy: a stuck group
must not grow the shared database without bound, and the log line is the signal.

### Dependency-direction check

The new module depends on `messaging-spring-boot-starter` (compile) and `outbox-spring-boot-starter`
(compile, for `OutboxDispatcher`, `OutboxMessage` and `KafkaOutboxDispatcher`). It also depends on
`db-core` and `job-core` (compile) and on `architecture-rules` and `test-support` (test).

- Does `messaging-spring-boot-starter` already depend on `messaging-db-spring-boot-starter`, directly
  or transitively? Its `inRepoDependents` (identity-projection, notification-service, outbox,
  user-settings) and their dependents form the set to check, and the new module is in none of them,
  because it does not exist yet and nothing will be added that depends on it. **No cycle.**
- The same holds for `outbox-spring-boot-starter`: its `inRepoDependents` (audit, crud-service-example,
  export, file-action, file-ingest, notification-service, user-settings) do not contain the new
  module. **No cycle.**
- **Nothing in the repository will depend on the new module.** Services opt in from their own POMs,
  which is outside this change. The new ArchUnit rule (D9) keeps any class from depending on it.

`project-index.json` is stale, so these answers come from `scripts/manifest.sh module`. Task 2.3
regenerates the manifest and re-checks them.

### POM changes

- **`pom.xml` (root):** one `<module>sources/messaging-db-spring-boot-starter</module>`.
- **`ludwig-bom`:** one managed entry for `ru.ludwigandreas:messaging-db-spring-boot-starter:${project.version}`,
  next to `messaging-spring-boot-starter`. This is an in-repo artifact, not a third-party version.
- **`ludwig-service-parent`:** no change.
- **Module POM:** parented by `common` with `<relativePath>../../pom.xml</relativePath>`, as
  `repository-layout` requires.

### D9. The convention this introduces, and its check

The convention is "nothing outside the starter depends on it". It is checked by a new ArchUnit rule
in `architecture-rules` (owned by ArchUnit, per `enforcement-triad`): classes residing outside
`ru.ludwigandreas.messaging.db..` should not depend on classes inside it. The rule follows the
`classes().should(notDependOnClassesThat…)` form that `KafkaRules` documents, because `noClasses()`
reports nothing with that condition. The starter's package sits under `ru.ludwigandreas.messaging`,
so the existing `clients-are-confined-to-messaging` rule treats its Kafka API use as messaging code.
Task 1.5 checks that assumption.

No mechanical check is possible for "routes keep saying `kafka`" or "listeners stay unchanged". Both
follow from D1, and the integration test in group 5 proves them by running an unmodified listener.

## Risks / Trade-offs

- **[A claim holds a connection while the listener runs]** → for one record only (D4), and the
  listener's own transaction uses a second connection. Pools need `concurrency × 2` per log listener,
  which the README states.
- **[Polling load on the shared database]** → one claim query per container per interval, which an
  index on `consumer_offset` (`group_id`, `next_attempt_at`) covers. With five services, three groups
  each and 8 partitions, that is still under 100 cheap queries per second.
- **[Spring Kafka internals for D2's extraction]** → the spike decides the approach before anything
  is built on it. The fallback is named in D2.
- **[Per-key ordering is broken while draining]** → during `draining`, one key can have records in
  both the log backlog and Kafka, and they interleave. This is documented in the README cutover
  section: drain at a quiet time, and dedup covers duplicates but not reordering.
- **[Purge deletes unconsumed records]** → this is a deliberate policy (D8), logged per group. It is
  configurable through `retention`.
- **[Startup with no reachable broker]** → in `active` mode, with every consumed topic either listed
  or external, nothing must connect to a broker at startup. Boot's `KafkaAdmin` only connects when
  `NewTopic` beans exist, and the outbox's `KafkaTemplate` connects lazily. The spike verifies this
  against the real autoconfiguration set.

## Migration Plan

1. **Adoption.** A service adds the dependency and declares the cluster the log stands in for (R3). The
   tables are created on first start.
2. **Rollback of adoption.** Remove the dependency. Records still in the log are not delivered (see
   step 3 for the safe path).
3. **Cutover to an owned cluster next year:**
   1. Set `mode=draining` on every service, in any order.
   2. Wait until every group's log-lag metric reads 0.
   3. Remove the dependency and its properties.
   4. Drop the schema with the SQL in the README. Liquibase cannot do this, because the changesets
      leave with the jar.

## Revision: named clusters (after add-kafka-clusters)

The first implementation (groups 1-9 of tasks.md) replaced the single `kafka` transport and chose per
topic. `add-kafka-clusters` makes clusters first-class. This starter then replaces **named clusters**,
and these decisions change:

- **R1, writes (supersedes D1's producer side and D5's Kafka-read dead-letter redirect).** The starter
  replaces `KafkaClusterProducers` with a decorator. For a replaced cluster it returns a producer
  factory whose producers serialize with the given serializers and append the bytes to the log.
  Outbox writes and dead letters to that cluster both reach the log through this one seam. The
  `kafkaOutboxDispatcher` bean override and the `CommonErrorHandler` swap are deleted.
- **R2, reads (supersedes D2's topic split).** The routing factory reads the consumer's
  `ResolvedConsumerSettings.cluster()`. A consumer on a replaced cluster gets a `DbLogListenerContainer`
  for all its topics, and anything else gets the original Kafka container. `NarrowedEndpoint` and the
  per-topic `CompositeListenerContainer` split are deleted. The composite stays only for `draining`
  (log + cluster).
- **R3, configuration.** `ludwig.messaging.db.consume` is removed. `ludwig.messaging.db.clusters`
  (default `[internal]`) names the replaced clusters. A replaced cluster needs no address until
  `draining`.
- **R4, the open question below is answered.** Draining writes go to the replaced cluster's own
  address from `ludwig.kafka.clusters.<name>`, not to `spring.kafka.bootstrap-servers`.

## Open Questions

None. The in-house cluster's address is answered by R4.
