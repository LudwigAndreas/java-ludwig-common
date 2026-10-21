## 1. Cluster model (messaging; design D1, D2)

- [x] 1.1 `KafkaClusters`: bind `ludwig.kafka.clusters.<name>` on top of `spring.kafka` into one
      `KafkaProperties` per cluster. Refuse a cluster named `default`, and names longer than 44 characters.
      Proof: `mvn -pl :messaging-spring-boot-starter -am verify -Dtest=KafkaClustersTest`
- [x] 1.2 `KafkaClusterProducers` (default implementation, `@ConditionalOnMissingBean`). Proof:
      `mvn -pl :messaging-spring-boot-starter -am verify -Dtest=DeadLetterTemplatesTest` (the default producers are exercised through the templates) and `-Dit.test=MultiClusterConsumerIT`
- [x] 1.3 `DeadLetterTemplates.create` takes a producer factory from `KafkaClusterProducers`. Proof:
      `mvn -pl :messaging-spring-boot-starter -am verify -Dtest=DeadLetterTemplatesTest`

## 2. Consumers per cluster (messaging; D3, D6, D7)

- [x] 2.1 `cluster` and `dead-letter.cluster` in `ConsumerSettings` / `ResolvedConsumerSettings`
      (defaults `default` / `source`). Undeclared clusters, and `non-blocking` off `default`, fail
      startup. Proof: `mvn -pl :messaging-spring-boot-starter -am verify -Dtest=MessagingPropertiesTest,MessagingConfigurationValidatorTest`
- [x] 2.2 The builder builds the consumer factory from the consumer's cluster, and a lazily built
      dead-letter template per target cluster. Proof:
      `mvn -pl :messaging-spring-boot-starter -am verify -Dit.test=MultiClusterConsumerIT` (two Kafka
      containers: delivery per cluster, dead letter to `source` and to another cluster)
- [x] 2.3 A `cluster` tag on every `ludwig.messaging.*` meter, with `AuditingDeadLetterRecoverer`
      passing it. Record the `MessagingMetrics` change as an API-baseline exception. Proof:
      `mvn -pl :messaging-spring-boot-starter -am verify -Dit.test=MultiClusterConsumerIT` (series per
      cluster). No API-baseline exception: breaking changes are allowed in this major release
- [x] 2.4 Existing single-cluster behaviour unchanged. Proof:
      `mvn -pl :messaging-spring-boot-starter -am verify` (the existing `SharedConsumerIT` unmodified)

## 3. Cluster-scoped dedup (idempotency; D5)

- [x] 3.1 `IdempotencyScopes.forTopic(cluster, topic)` and `IdempotentRecordFilterStrategy.forCluster`,
      with `default` keeping today's scope. Proof:
      `mvn -pl :idempotency-spring-boot-starter -am verify -Dtest=ClaimContractTest -Dit.test=ConsumerDedupIT` (real PostgreSQL store)
- [x] 3.2 The builder binds the filter to the consumer's cluster. Proof:
      `mvn -pl :messaging-spring-boot-starter -am verify -Dit.test=MultiClusterConsumerIT` (same topic,
      partition and offset on two clusters, neither discarded)

## 4. Outbox per cluster (outbox; D4)

- [x] 4.1 `OutboxDispatcherGroup` accepted by `OutboxDispatcherRegistry`, with duplicates still refused.
      Proof: `mvn -pl :outbox-spring-boot-starter -am verify -Dtest=OutboxDispatcherRegistryTest`
- [x] 4.2 `outboxKafkaTemplate` from `KafkaClusterProducers`, and one `KAFKA:<cluster>` dispatcher per
      declared cluster. A route naming an undeclared cluster fails startup. Proof:
      `mvn -pl :outbox-spring-boot-starter -am verify -Dtest=MultiClusterOutboxTest,OutboxDispatcherRegistryTest` (two Kafka
      containers, two routes, plain `kafka` unchanged; route validation)

## 5. Correlation id on outbox records (observability, outbox)

- [x] 5.1 Observability publishes a `CorrelationProvider` over `CorrelationContext`
      (`@ConditionalOnMissingBean`, ordered before `AuditCoreAutoConfiguration`). Proof:
      `mvn -pl :observability-spring-boot-starter -am verify -Dtest=ObservabilityCorrelationProviderTest`
- [x] 5.2 `DefaultOutboxEventPublisher` stamps the current correlation id under messaging's correlation
      header name unless the event already carries one; correct `KafkaOutboxDispatcher`'s comment. Proof:
      `mvn -pl :outbox-spring-boot-starter -am verify -Dtest=DefaultOutboxEventPublisherTest` and
      `-Dtest=MultiClusterOutboxTest` (the record carries `X-Correlation-Id`)

## 6. Single producer seam (architecture-rules)

- [x] 6.1 `kafka.producers-come-from-the-cluster-seam`: nothing outside `ru.ludwigandreas.messaging..`
      constructs a `DefaultKafkaProducerFactory`. Add bad and good fixtures, and document it in both
      READMEs. Proof: `mvn -pl :architecture-rules -am verify`

## 7. Documentation

- [x] 7.1 messaging README (en/ru): clusters, per-consumer cluster and dead-letter cluster, the dedup
      caveat for custom filters, and the retry-topic limitation. Proof:
      `diff <(grep -c '^#' sources/messaging-spring-boot-starter/README.md) <(grep -c '^#' sources/messaging-spring-boot-starter/README.ru.md)`
- [x] 7.2 outbox README (en/ru): `kafka:<cluster>` transports. Proof: the same heading-count parity check
      on the outbox READMEs
- [x] 7.3 CHANGELOG (en/ru). Proof: `openspec validate add-kafka-clusters --strict`

## 8. Verification gate

- [x] 8.1 `mvn -q validate`
- [x] 8.2 `scripts/check_migrations.sh` and `scripts/manifest.sh stale`
- [x] 8.3 `mvn -pl :<module> -am verify` for messaging, outbox, idempotency, observability and architecture-rules
- [x] 8.4 `mvn -pl :<dependent> -am verify` for every dependent named by `scripts/manifest.sh module` of
      each touched module
- [x] 8.5 `openspec validate add-kafka-clusters --strict`
