## 1. Spike: Springwolf against this platform (design D1)

- [x] 1.1 Pin a Springwolf version compatible with Boot 3.3.5 and spring-kafka 3.2.4 in `ludwig-bom`, and
      confirm three things in a throwaway test: `ConsumerRecord<String, T>` listeners resolve to `T`, a
      customizer runs after the scan, and the document is served as YAML or JSON. Record the outcome in
      design.md. Proof: `mvn -pl :asyncapi-spring-boot-starter -am verify -Dit.test=SpringwolfSpikeIT`

## 2. Shared document check (test-support; D3)

- [x] 2.1 `PublishedDocument`: canonicalize, write to target, compare with `docs/api/`, write under
      `-Dludwig.apidocs.write=true`, fail with the refresh command. Proof:
      `mvn -pl :test-support -am verify -Dtest=PublishedDocumentTest`
- [x] 2.2 Move both services' `ApiDocumentIT` onto it, with the committed OpenAPI documents unchanged.
      Proof: `mvn -pl :crud-service-example -am verify -Dit.test=ApiDocumentIT` and the same for
      `notification-service`, then `git diff --exit-code docs/api/*.openapi.yaml`

## 3. Consumer names and outbox event types (messaging, outbox; D1, D2)

- [x] 3.0 `ListenerContainerFactoryBuilder.consumerNameOf(factory)`, recorded for every factory it builds.
      Proof: `mvn -pl :messaging-spring-boot-starter -am verify -Dtest=MessagingAutoConfigurationTest`

- [x] 3.1 `OutboxEventTypes` (merge; conflicting declarations fail startup) and the `require-declared`
      check in `DefaultOutboxEventPublisher`. Proof:
      `mvn -pl :outbox-spring-boot-starter -am verify -Dtest=OutboxEventTypesTest,DefaultOutboxEventPublisherTest`

- [x] 3.2 audit, export, file-ingest and user-settings declare the outbox event types they publish
      (D2 revision). Proof: `mvn -pl :crud-service-example -am verify` passes with `require-declared=true`,
      and each module's own `-am verify`

## 4. The starter (asyncapi-spring-boot-starter; D1)

- [x] 4.1 Module skeleton (parent `common`, `<relativePath>../../pom.xml</relativePath>`), reactor
      `<module>`, BOM entry, README (en/ru). Proof: `scripts/manifest.sh build && scripts/manifest.sh layout`
      and `mvn -q validate`
- [x] 4.2 The envelope, consumer, producer and server customizers. Proof:
      `mvn -pl :asyncapi-spring-boot-starter -am verify -Dit.test=AsyncApiDocumentShapeIT` (a test
      application with a typed consumer on a named cluster, a text consumer, and a declared event routed
      over `kafka:partner-a`, asserting each spec scenario on the generated document)
- [x] 4.3 Determinism: the same context documented twice canonicalizes identically. Proof: the same IT,
      plus `PublishedDocumentTest` for member-order independence. (Two separate contexts with a different
      bean order are not exercised; each service's drift check passing on repeated builds is the field proof.)

## 5. Services

- [x] 5.1 `crud-service-example`: depend on the starter, declare its event types, set
      `require-declared=true` in test properties, add `AsyncApiDocumentIT`, commit
      `docs/api/crud-service-example.asyncapi.yaml`. Proof: `mvn -pl :crud-service-example -am verify`
- [x] 5.2 `notification-service`: the same. Proof: `mvn -pl :notification-service -am verify`

## 6. Script (D4)

- [x] 6.1 `scripts/api-docs.sh refresh | check | publish --version <v>`, with the missing-document check
      and the snapshot/release choice. Proof: `scripts/api-docs.sh check`, and
      `scripts/api-docs.sh publish --version 0.0.0-SNAPSHOT --dry-run` printing the `deploy:deploy-file`
      commands it would run
- [x] 6.2 Add `scripts/api-docs.sh missing` (D4 revision) to `scripts/gate.sh`'s commands. Proof: `scripts/gate.sh --list`

## 7. Documentation

- [x] 7.1 `docs/api/README.md`: both document kinds, the script, the Nexus coordinates. Proof: review,
      plus `openspec validate add-published-async-document --strict`
- [x] 7.2 Outbox README (en/ru): `OutboxEventTypes` and `require-declared`. Proof: heading-count parity
- [x] 7.3 CHANGELOG (en/ru). Proof: `openspec validate add-published-async-document --strict`

## 8. Verification gate

- [x] 8.1 `mvn -q validate`, `scripts/manifest.sh stale`, `scripts/manifest.sh layout`, `scripts/api-docs.sh check`
- [x] 8.2 `mvn -pl :<module> -am verify` for asyncapi-spring-boot-starter, messaging-spring-boot-starter, outbox-spring-boot-starter,
      test-support, crud-service-example and notification-service
- [x] 8.3 `mvn -pl :<dependent> -am verify` for every in-repo dependent of messaging, outbox and test-support (test-support-local: StubFaultIT.slow fails on HEAD too; see receipt)
- [x] 8.4 `openspec validate add-published-async-document --strict`
