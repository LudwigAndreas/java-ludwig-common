# test-support

***English** · [Русский](README.ru.md)*

The one place this platform's container images are written down, and the containers and fixtures a container test
needs: digest-pinned `DockerImageName` constants loaded from `images.properties`, one shared
PostgreSQL/Kafka/LocalStack/Redis singleton per JVM, `@ServiceConnection` wiring, composed test annotations, a
truncate-between-tests helper, in-process SMTP and HTTP stubs, a movable `Clock`, and the published-API-document check.

| | |
|---|---|
| Role | test support (test-scoped jar) |
| Depends on (in-repo) | nothing - and must not |
| Used by | nearly every module with integration tests (see `PROJECT_INDEX.md`) |
| Surfaces | none |

## Usage

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>test-support</artifactId>
    <scope>test</scope>
</dependency>
```

```java
@LudwigPostgresTest
class SomethingIntegrationTest {
    // the context is wired to a running, digest-pinned PostgreSQL
}
```

or alongside an existing `@SpringBootTest`:

```java
@SpringBootTest(classes = TestApplication.class)
@Import(PostgresContainerConfiguration.class)
class SomethingIntegrationTest { }
```

**Name container tests `*IT` or `*IntegrationTest`**: surefire excludes those names and failsafe runs only them, so a
container test named otherwise runs in the wrong phase (and fails without Docker).

| Type | What it is |
|---|---|
| `LudwigTestImages` | `POSTGRES`, `KAFKA`, `LOCALSTACK`, `REDIS` as `DockerImageName` from `images.properties`, with `asCompatibleSubstituteFor(...)` applied |
| `Containers` | One container per image per JVM, started lazily; `stop()` and `close()` do nothing |
| `PostgresContainerConfiguration` | `@TestConfiguration` exposing PostgreSQL via `@ServiceConnection` |
| `KafkaPropertiesInitializer` | Points `spring.kafka.bootstrap-servers` at the shared broker |
| `LocalStackS3Initializer` / `LocalStackS3` | Points `ludwig.storage.s3.*` at LocalStack; SDK client plus idempotent bucket create/clear |
| `Containers.redis()` | Shared Redis (cache shared tier); read the mapped port from the container (`REDIS_PORT` is the internal port) |
| `@LudwigPostgresTest`, `@LudwigKafkaTest`, `@LudwigLocalStackTest` | Composed annotations |
| `DatabaseCleaner` | `TRUNCATE ... RESTART IDENTITY CASCADE` of every table except Liquibase's |
| `GreenMailFixture`, `WireMockFixture` | In-process SMTP and HTTP stubs (for stubs wired to a `@LudwigRestClient`, see [test-support-local](../test-support-local)) |
| `TestClock` | Mutable `Clock`: `advance(Duration)`, `set(Instant)`, fixed factory |
| `docs.PublishedDocument` | Behind every `ApiDocumentIT` / `AsyncApiDocumentIT`: canonicalises the generated document, writes `target/<kind>/`, compares with `docs/api/`, overwrites under `-Dludwig.apidocs.write=true` |

### Isolation with a shared container

A shared container does not give each class an empty database. Clean in `@BeforeEach`:

```java
@BeforeEach
void reset() {
    new DatabaseCleaner(dataSource).clean();
}
```

This matters most where the test exercises shared state (a lease, a rate-limit window, an outbox row).
`LocalStackS3.clearBucket(...)` is the S3 counterpart. A test that must stop a container creates its own from
`LudwigTestImages`.

## Configuration

Image pins in `src/main/resources/images.properties`, each with a `# renovate:` comment so a dependency bot can bump
it:

```properties
# renovate: datasource=docker depName=postgres
ludwig.test.image.postgres = postgres:16-alpine@sha256:cf78e766...
```

`LudwigTestImages` refuses to initialise if a pin lacks `@sha256:` with a 64-character hex digest. Optional:
`testcontainers.reuse.enable=true` in `~/.testcontainers.properties` reuses containers across runs locally (every
singleton is `withReuse(true)`); keep it off in CI (a reused container carries previous rows).

## Deployment

Not applicable (test scope only). CI agents need a Docker daemon; with no daemon, container tests **error** (they do
not skip).

## Observability

Not applicable.

## Audit

Not applicable.

## Runbook

| Symptom | Likely cause | What to do |
|---|---|---|
| `Could not find a valid Docker environment` | Docker not running | Start Docker (required for `mvn verify` / `mvn clean install`) |
| `LudwigTestImages` fails to initialise | A pin without a digest | Add `@sha256:<64 hex>` to the pin |
| Tests pass alone, fail together | Shared container state | Add `DatabaseCleaner` / bucket clear in `@BeforeEach` |
| `connection refused` in later test classes | Something stopped a shared container (should be impossible) | Do not create containers with `@Container` from `Containers`; create your own from `LudwigTestImages` |
| Updating an image | CVE respin or upgrade | Change the one line in `images.properties` (with digest), run `scripts/check_image_pins.sh` and the affected suites |

## Testing

A unit test that a digest-less pin is rejected, and `PostgresFixtureIntegrationTest`, which boots a context through
`@LudwigPostgresTest` (proves `@ServiceConnection` resolves against a digest-pinned image name). No coverage gate:
its classes are exercised by other modules' suites.

## Design decisions

- **Digests in one properties file**, not Java constants: one-line bot updates, one place to audit.
- **One container per JVM per image**: Maven runs one reused fork per module, so classes in a module share it; modules
  do not share across JVMs.
- **Containers cannot be stopped by a borrower** (Spring, JUnit and try-with-resources all try); Ryuk removes them at
  JVM exit.
- **No in-repo dependencies**: Maven's reactor DAG includes test scope, and `db-core`/`audit-core` use this module.
- **Not a `test-jar`**; an ordinary jar at test scope.
- **No security fixtures** (they need `LudwigPrincipal`; see [test-support-security](../test-support-security)), no
  containerised GreenMail/WireMock (in-process libraries), no bucket or topic creation (consumer vocabulary).
