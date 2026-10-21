## 1. Spike: confirm the extension points (design D1, D2, D5, Risks)

*Scope change, recorded in state.json `decisions`: the facts were settled with `javap` against
spring-kafka 3.2.4 before any code was written. They are proven by the real integration tests rather
than by throwaway spike tests, and each task names the test that now proves it. Integration commands run
with `-Dtest=none -Dsurefire.failIfNoSpecifiedTests=false`.*

- [x] 1.1 A `BeanPostProcessor` that replaces a `ConcurrentKafkaListenerContainerFactory` bean with a
      different `KafkaListenerContainerFactory` is honoured by `@KafkaListener(containerFactory=…)`.
      Proof: `mvn -pl :messaging-db-spring-boot-starter -am verify -Dit.test=LogOnlyTransportIT`
- [x] 1.2 A delegating endpoint that narrows `getTopics()` produces a Kafka container subscribed to
      only those topics. Proof: `mvn -pl :messaging-db-spring-boot-starter -am verify -Dit.test=MixedTransportIT`
- [x] 1.3 From an unstarted container built by the builder's factory, reach the adapted
      `MessageListener` (with the dedup filter) and the key and value `Deserializer`. The
      `RecordInterceptor` is protected, so it is composed from the context instead (design D5). Proof:
      `mvn -pl :messaging-db-spring-boot-starter -am verify -Dit.test=LogOnlyTransportIT` (dedup,
      interceptor, poison-payload and version-gate cases)
- [x] 1.4 Find a dead-letter redirect for Kafka-read records that declares no
      `DeadLetterPublishingRecoverer` (design D5). Proof:
      `mvn -pl :messaging-db-spring-boot-starter -am verify -Dit.test=MixedTransportIT`, plus `ArchitectureTest`
- [x] 1.5 A context with messaging and outbox on the classpath, an unreachable bootstrap address and
      only log topics starts and works without a broker connection, and the existing `KafkaRules` pass on
      the module. Proof: `mvn -pl :messaging-db-spring-boot-starter -am verify -Dit.test=LogOnlyTransportIT`
      (`bootstrap-servers=localhost:9`), plus `ArchitectureTest`

## 2. Module skeleton

- [x] 2.1 Create `sources/messaging-db-spring-boot-starter` (parent `common`,
      `<relativePath>../../pom.xml</relativePath>`, package `ru.ludwigandreas.messaging.db`), with
      `README.md` and `README.ru.md`. Proof: `scripts/manifest.sh layout` and
      `mvn -pl :messaging-db-spring-boot-starter -am verify`
- [ ] 2.2 Add the `<module>` to the root `pom.xml` and the managed entry to `build/ludwig-bom/pom.xml`.
      Proof: `mvn -q validate`. *Blocked, not passed:* the reactor validate stops in
      `security-spring-boot-starter` on an unused import in uncommitted work outside this change.
- [x] 2.3 Regenerate the manifest and re-check the dependency direction from design.md. Proof:
      `scripts/manifest.sh build && scripts/manifest.sh stale && scripts/manifest.sh module sources/messaging-db-spring-boot-starter`
- [x] 2.4 Add `MessagingDbProperties` (`mode`, `consume`, `partitions`, `poll-interval`, `retention`,
      `purge-interval`, `schema`) with validation. Proof:
      `mvn -pl :messaging-db-spring-boot-starter -am verify -Dtest=MessagingDbPropertiesTest`

## 3. Log schema and append path (design D3, D6)

- [x] 3.1 Liquibase formatted-SQL changesets for the schema, `message_log`, `partition_head` and
      `consumer_offset`, each with `dbms:postgresql`, an advisory lock and `IF NOT EXISTS`, and
      `--rollback NOT REQUIRED` (design D6). Add the `messagingDbLiquibase` bean. Proof:
      `scripts/check_migrations.sh` and `mvn -pl :messaging-db-spring-boot-starter -am verify -Dit.test=SchemaInstallIT`
      (sequential and concurrent, with separate changelog tables)
- [x] 3.2 The append repository: head-row lock, offset assignment, murmur2 partitioning. Proof:
      `mvn -pl :messaging-db-spring-boot-starter -am verify -Dit.test=LogStoreIT`, including the blocked
      second writer and the partitioner checked against Kafka's own `BuiltInPartitioner`
- [x] 3.3 The `kafkaOutboxDispatcher` bean: the outbox's own dispatcher over a log-appending template in
      `active`, over the outbox's template in `draining`, ordered before `OutboxKafkaAutoConfiguration`.
      Proof: `mvn -pl :messaging-db-spring-boot-starter -am verify -Dit.test=LogOnlyTransportIT,DrainingIT`

## 4. Log consumer (design D2, D4, D5, D7)

- [x] 4.1 `DbLogListenerContainer`: position bootstrap per reset policy, `SKIP LOCKED` claim, ordered
      read, interceptor then listener, per-record commit. Proof:
      `mvn -pl :messaging-db-spring-boot-starter -am verify -Dit.test=LogStoreIT,LogOnlyTransportIT`
- [x] 4.2 Retry and backoff through `next_attempt_at`, the attempt budget, non-retryable classes, the
      dead-letter append, and the `dead.lettered`/`dropped` metrics and audit. Proof:
      `mvn -pl :messaging-db-spring-boot-starter -am verify -Dit.test=LogOnlyTransportIT` (counts
      deliveries; covers poison bytes and a failed dead-letter write)
- [x] 4.3 `RoutingListenerContainerFactory`, the `BeanPostProcessor` and `CompositeListenerContainer`,
      which split topics by `consume` and `mode`. Proof:
      `mvn -pl :messaging-db-spring-boot-starter -am verify -Dit.test=MixedTransportIT`
- [x] 4.4 The dead-letter redirect for Kafka-read records in `active` mode. Proof:
      `mvn -pl :messaging-db-spring-boot-starter -am verify -Dit.test=MixedTransportIT` (the record is in
      the log, and a probe consumer finds nothing on Kafka's `.dlt`)
- [x] 4.5 Startup validation: retry topics or a `topicPattern` on log topics, and a factory not built by
      the builder, fail and name the consumer and topic. (The missing-group-id refusal exists in code
      but has no test.) Proof:
      `mvn -pl :messaging-db-spring-boot-starter -am verify -Dtest=StartupValidationTest`

## 5. Consumer guarantees end to end

- [x] 5.1 Fan-out to two groups, both reset policies, per-key ordering across concurrent workers in one
      group, and a dead worker's partition being taken over. Proof:
      `mvn -pl :messaging-db-spring-boot-starter -am verify -Dit.test=LogStoreIT,LogOnlyTransportIT`.
      Ordering is proven with three workers of one instance (`concurrency = "3"`). They contend through
      the same `SKIP LOCKED` claim three instances would use, but it is not three JVMs.
- [x] 5.2 Unmodified listeners built from `ListenerContainerFactoryBuilder.forJsonPayload` and
      `forTextPayload` read the log. Dedup filters a duplicate idempotency key, the context's record
      interceptors run around the listener, and the version gate dead-letters an out-of-range version.
      Proof: `mvn -pl :messaging-db-spring-boot-starter -am verify -Dit.test=LogOnlyTransportIT`

## 6. Draining, lag and purge (design D8, Migration Plan)

- [x] 6.1 `draining`: writes go to Kafka, listed topics are read from both, and the log lag reaches 0
      per group. Proof: `mvn -pl :messaging-db-spring-boot-starter -am verify -Dit.test=DrainingIT`
      (10 unconsumed records, then lag 0)
- [x] 6.2 Purge: count the records each group has not consumed, then delete in batches. Proof:
      `mvn -pl :messaging-db-spring-boot-starter -am verify -Dit.test=LogStoreIT`. The store methods
      are proven; `LogPurgeJob`'s schedule wiring is not exercised by a test.

## 7. Removability rule (design D9)

- [x] 7.1 Add `kafka.nothing-depends-on-the-db-log-transport` to `architecture-rules`, with a bad
      fixture and the good one, and document it in both READMEs. Proof: `mvn -pl :architecture-rules -am verify`

## 8. Documentation

- [x] 8.1 Write `README.md` for the starter: why it exists and that it is temporary; configuration
      reference; the semantics kept and refused; the cutover runbook; ordering during draining; the
      purge policy; the schema-drop SQL. Proof: `mvn -pl :messaging-db-spring-boot-starter -am verify`,
      plus a review against spec headings
- [x] 8.2 Write `README.ru.md` with the same section set as `README.md`. Proof:
      `diff <(grep '^#' sources/messaging-db-spring-boot-starter/README.md | wc -l) <(grep '^#' sources/messaging-db-spring-boot-starter/README.ru.md | wc -l)`
- [x] 8.3 Add CHANGELOG entries (en/ru). Proof: `openspec validate add-db-log-transport --strict`

## 9. Verification gate

- [ ] 9.1 `mvn -q validate`. *Blocked by the same `security-spring-boot-starter` Checkstyle error (see 2.2).*
- [x] 9.2 `scripts/manifest.sh stale && scripts/manifest.sh layout`
- [x] 9.3 `mvn -pl :messaging-db-spring-boot-starter -am verify`
- [ ] 9.4 `mvn -pl :architecture-rules -am verify`, then every dependent listed by
      `scripts/manifest.sh module build/architecture-rules`, each with `mvn -pl :<artifactId> -am verify`.
      *Partial:* `architecture-rules`, `messaging`, `file-ingest`, `object-storage` and `odata-filter` pass.
      `crud-service-example`, `file-action`, `grpc-client`, `grpc-server`, `notification-service`, `pat` and
      `user-settings` never reached their own modules: each `-am` build stops at the same
      `security-spring-boot-starter` Checkstyle error.
- [x] 9.5 `openspec validate add-db-log-transport --strict`

## 10. Revision: replace named clusters (after add-kafka-clusters; design R1-R4)

- [ ] 10.1 Replace `KafkaClusterProducers` for the clusters in `ludwig.messaging.db.clusters` with a
      log-appending producer factory; delete the `kafkaOutboxDispatcher` override and the dead-letter
      handler swap. Proof: `mvn -pl :messaging-db-spring-boot-starter -am verify -Dit.test=LogOnlyTransportIT,DrainingIT`
- [ ] 10.2 Route containers by the consumer's cluster; delete `NarrowedEndpoint` and the per-topic split.
      Proof: `mvn -pl :messaging-db-spring-boot-starter -am verify -Dit.test=MixedTransportIT` (rewritten
      as two listeners on two clusters, with the dead letter of the partner consumer in the log)
- [ ] 10.3 Remove `ludwig.messaging.db.consume`, add `ludwig.messaging.db.clusters`, and refuse a
      pattern subscription on a replaced cluster. Proof:
      `mvn -pl :messaging-db-spring-boot-starter -am verify -Dtest=MessagingDbPropertiesTest,StartupValidationTest`
- [ ] 10.4 README (en/ru): per-cluster adoption; remove the "separate cluster" paragraph; draining per
      cluster. Proof: the heading-count parity check
- [ ] 10.5 The full gate again (group 9), with `add-kafka-clusters` merged
