# Developer guide

**English** · **[Русский](developer-guide.ru.md)**

For engineers who write platform modules or services on top of them. The process (branches, OpenSpec,
the gate, versions, releases) is in [runbook.md](runbook.md) and is not repeated here; this guide covers
what to build and how it must look. The authoritative rules for each module live in its own README.

## 1. Architecture in one page

```text
build/      build artifacts: ludwig-bom, ludwig-service-parent, ludwig-grpc-api-parent,
            architecture-rules, checkstyle-rules, jacoco-aggregate
sources/    libraries (*-core), starters (*-spring-boot-starter), contracts (*-grpc-api),
            test support (test-support*)
services/   runnable services: crud-service-example (reference), notification-service
```

| Kind | Naming | Parent | Contains |
|---|---|---|---|
| Core library | `<name>-core` | reactor root | plain Java contracts and algorithms; Spring only if unavoidable, never auto-configuration |
| Starter | `<name>-spring-boot-starter` | reactor root | auto-configuration, `@ConfigurationProperties`, Liquibase changelog, message bundles, metrics |
| Contract | `<service>-grpc-api` | `ludwig-grpc-api-parent` | `.proto` files only |
| Test support | `test-support*` | reactor root | fixtures used at `test` scope |
| Service | any | `ludwig-service-parent` | application, `deploy/`, `Dockerfile` if it ships one |

**Dependencies point down and never form a cycle.** `audit-core`, `job-core`'s mechanics,
`cache-spring-boot-starter`, `test-support` and `pat-core` have no in-repo dependencies on purpose,
so everything else can use them. Check both directions before adding an edge:
`scripts/manifest.sh module sources/<module>`. `PROJECT_INDEX.md` is the generated map.

**Build on the platform primitives instead of writing your own:**

| You need | Use | Never |
|---|---|---|
| a scheduled, database-backed worker | `job-core`: `SelfSchedulingLifecycle`, `SKIP LOCKED` claims, `RunLock` | `@Scheduled` loops, advisory locks |
| to publish an event | `outbox-spring-boot-starter`, with the event type declared | `KafkaTemplate.send` in a transaction |
| to consume an event | `messaging-spring-boot-starter` container factory | a hand-built factory or error handler |
| dedup of a caller-supplied key | `idempotency-spring-boot-starter` | read-then-insert |
| an audit record | `audit-core` `AuditSink` | a module-specific audit SPI or table |
| a cache | `cache-spring-boot-starter`, with a declared purpose | a private Caffeine instance |
| an error response | a `web-core` exception mapper + message bundle | a `@RestControllerAdvice` |
| a long-running HTTP operation | `web-core`'s operation contract (`OperationResponse`, `202` + `Location`) | a custom polling shape |
| outbound HTTP or gRPC | `rest-client` / `grpc-client` named clients | `new RestTemplate()` |
| files in a bucket | `object-storage-spring-boot-starter` | the AWS SDK directly |

## 2. Writing a new library or starter

1. **Write the OpenSpec change first** (`openspec new change <name>`), with a spec for the capability
   other modules will rely on. See [runbook.md §2](runbook.md#2-adding-a-feature).
2. **Create `sources/<name>/pom.xml`** with the reactor root as parent and
   `<relativePath>../../pom.xml</relativePath>`. Versions come from `ludwig-bom`; declare none.
3. **Add the module** to the root `pom.xml` `<modules>`, to `ludwig-bom`, and to
   `build/jacoco-aggregate`'s dependencies; then run `scripts/manifest.sh build` to regenerate
   `PROJECT_INDEX.md`.
4. **Package layout:** `ru.ludwigandreas.<name>`; auto-configuration in `...autoconfigure`, listed in
   `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
5. **Configuration:** one `@Validated @ConfigurationProperties("ludwig.<name>")` tree with the
   configuration processor on the path. Every default lives in that class, not in YAML. Relationships
   between settings that are individually valid but jointly wrong are refused at startup by a
   validator that reports every problem at once.
6. **Conditional wiring:** a starter on the classpath with nothing enabled must not change or break
   the application (`openspec/specs/starter-conditional-wiring`). Order auto-configurations with
   `after`/`before` when one is conditional on another's beans.
7. **Persistence:** ship a Liquibase changelog under `db/changelog/<name>/` inside the jar, with
   formatted-SQL changesets named `NNNN-<slug>.sql` (id `<prefix>-NNNN-<slug>`, author `ludwig-<prefix>`)
   and an XML root holding only includes; document the include line the service adds. Never edit a
   released changeset; add a new one. `scripts/check_migrations.sh` enforces the format.
8. **Errors and text:** problem codes are `ludwig.<name>.error.<reason>`; every user-facing string is
   in `i18n/ludwig-<name>-messages.properties` and `_ru.properties` with identical key sets
   (`openspec/specs/i18n-bundles`).
9. **Metrics:** Micrometer as an optional dependency, a `<Name>Metrics` interface with a `Noop`
   implementation always registered, so call sites record unconditionally. Meter names start with
   `ludwig.<name>.`; tags are bounded (never a user id, key or template name).
10. **Health:** if the module owns a backlog or an external connection, contribute a
    `HealthIndicator` and document which readiness group it belongs in.
11. **Audit:** state mutations and security decisions emit an `AuditEvent` through the injected
    `AuditSink`; see §5.
12. **Tests:** unit tests next to the code; Testcontainers tests named `*IT` or `*IntegrationTest`
    using `test-support`; an architecture test using `architecture-rules`.
13. **README.md and README.ru.md** from the template in
    [documentation-guide.md](documentation-guide.md), in the same change.

## 3. Writing a new service

1. `services/<name>/pom.xml` with `ludwig-service-parent` as parent (and `<relativePath/>` if it may
   build outside this reactor).
2. Take `crud-service-example` as the starting point for layout: `web` → `service` → `repository`,
   DTO / domain / entity models mapped with MapStruct, QueryDSL for queries, `settings` for typed
   properties.
3. Add `observability-spring-boot-starter` explicitly; nothing brings it in transitively.
4. **Readiness group:** include `db` and any backlog indicator:
   ```yaml
   management.endpoint.health.group.readiness.include: readinessState, db, <yourIndicator>
   ```
   Liveness checks only the process; a down partner must never restart pods.
5. **Published API documents:** add `ApiDocumentIT` (OpenAPI) and, if the service touches Kafka,
   `AsyncApiDocumentIT`, both based on `test-support`'s `PublishedDocument`; commit the output under
   `docs/api/` (see [api/README.md](api/README.md)).
6. **Deployment:** an image built by Jib from the parent, or a multi-stage `Dockerfile` with every base
   image pinned by digest; a manifest under `deploy/k8s/` modelled on notification-service's (probes,
   resources, `PodDisruptionBudget`, secrets by `secretKeyRef`).
7. **Local run:** add `test-support-local` and a `Local<Name>Application` under `src/test`, as
   notification-service does.
8. Endpoint paths are versioned (`/api/v1/...`) and use no dashes in a segment that names a
   resource: `/server/info`, not `/server-info`.

## 4. Writing a gRPC contract module

`sources/<service>-grpc-api/` with `ludwig-grpc-api-parent` as parent: the POM is the parent plus an
`artifactId`. Protos go in `src/main/proto/ludwig/<service>/v1/` and declare `package ludwig.<service>.v1`,
`java_package = "ru.ludwigandreas.<service>.grpc.v1"` and `java_multiple_files = true`. Every RPC states
its `idempotency_level`; the client starter retries only what the contract says is safe. Use
`ludwig/operation/v1/operation.proto` for long-running calls. `scripts/check_protos.sh` and
`scripts/manifest.sh layout` validate it. Reference: [catalog-grpc-api](../sources/catalog-grpc-api/README.md).

## 5. Observability and audit checklist for any new module

A module is not finished until an operator can tell whether it is working without reading its code.

| Question an operator asks | What the module must provide |
|---|---|
| Is it doing work? | a counter of processed items by outcome |
| Is it keeping up? | a backlog gauge (count and **age of the oldest item**), publishing zero when empty |
| Is it failing? | a failure counter by bounded reason; a `WARN`/`ERROR` log line with the correlation id |
| Is it stuck? | for workers: last-success timestamp or run counter (job-core gives none of its own) |
| Can it reach its dependencies? | a health indicator, in the readiness group only if the service cannot serve without it |
| Who changed what? | an `AuditEvent` for every state mutation driven by a person or a partner |
| What alert should fire? | a "Recommended alerts" table in the README, in Prometheus names |

Audit events: category from `AuditCategories` (add one to audit-core if none fits), action
`<category>.<verb>`, actor from `ActorResolver` or `Actor.system()`, no personal data in attributes
except through the `Redactor`. A failed audit write of a state mutation fails the operation (the
sink's failure policy); an observational event is logged and dropped.

Known gaps in existing modules are listed in [admin-guide.md §9](admin-guide.md#9-known-operational-gaps);
do not copy a pattern from that list.

## 6. Conventions enforced by the build

| Rule | Enforced by |
|---|---|
| Formatting, naming, Javadoc | `checkstyle-rules` at `validate` |
| Layering, cycles, persistence, Kafka, REST paths, logging | `architecture-rules` tests |
| Coverage ≥ 70% instructions / 60% branches (services) | `ludwig-service-parent` JaCoCo check |
| Published library API not broken without a major bump | revapi, `scripts/check_api_baseline.sh` |
| Every image reference pinned by digest | `scripts/check_image_pins.sh` |
| Changesets are formatted SQL in the uniform layout | `scripts/check_migrations.sh` |
| Module in the directory its role requires | `scripts/manifest.sh layout` |
| Published API documents match the code | `ApiDocumentIT`, `AsyncApiDocumentIT`, `scripts/api-docs.sh` |

What is written down but not enforced is listed in [harness-enforcement.md](harness-enforcement.md).
Run everything with `scripts/gate.sh --change <name> <module>`.

## 7. Testing

- `mvn test` runs unit tests only; `mvn verify` runs integration tests too and needs Docker.
- Use `@LudwigPostgresTest`, `@LudwigKafkaTest`, `@LudwigLocalStackTest`; containers are shared per
  JVM, so clean state with `DatabaseCleaner` in `@BeforeEach`.
- Security-aware tests use `test-support-security` (`TestPrincipalBuilder`, a reject-all `JwtDecoder`).
- Control time with `TestClock`, never `Thread.sleep`.
- See [test-support](../sources/test-support/README.md).

## 8. Writing documentation and runbooks

Follow [documentation-guide.md](documentation-guide.md): one README template for every module, both
locales in the same change, current state only (no history: that belongs in git and `CHANGELOG.md`),
"Not applicable" rather than an omitted section, and a runbook entry for every alert you recommend.
