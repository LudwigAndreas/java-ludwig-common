# test-support

***English** · [Русский](README.ru.md)*

One place for this platform's container images, the containers themselves, and the fixtures a container
test needs. Added as a single test-scoped dependency, it gives every module a digest-pinned
`DockerImageName`, a shared PostgreSQL/Kafka/LocalStack singleton per JVM, `@ServiceConnection`-based
wiring, a truncate-between-tests helper, an in-process SMTP and HTTP stub, and a `Clock` a test can move
by hand.

## Why

Two problems, and the second is bigger than the first.

**The digest was written down twenty-one times.** The platform's security policy is that every image is
identified by name, version *and* `sha256` digest, and that policy is correct: a tag can be re-pointed at
different content, so a test would silently change what it executes. The consequence nobody priced in is
that the digest then has to appear wherever a container is started. One `postgres:16-alpine` literal
appeared in twenty-one source files in **three different layouts** - on one line, split across two with a
string concatenation, and split plus fully qualified. A `sed` for the full literal finds the first
spelling and silently misses the other two, which leaves half the build on one digest and half on
another. Every new module paid the tax again, by copying a neighbour.

**Nineteen Postgres containers started per reactor build.** Nineteen separate
`new PostgreSQLContainer<?>(...)` instantiations, most of them `static @Container` fields, one per test
class. Container startup dominated the suite's wall clock, and three independent test bases -
`NotificationTestBase`, `FileIngestTestBase` and `PostgresBackedTest`, 416 lines between them - had each
invented a solution to the same problem, two of them having independently discovered the same subtle
failure along the way.

## What you get

| Type | What it is |
|---|---|
| `LudwigTestImages` | `POSTGRES`, `KAFKA`, `LOCALSTACK`, `REDIS` as `DockerImageName`, loaded from `images.properties`, with `asCompatibleSubstituteFor(...)` already applied |
| `Containers` | One container per image per JVM, started lazily, never stopped |
| `PostgresContainerConfiguration` | `@TestConfiguration` exposing Postgres via `@ServiceConnection` |
| `KafkaPropertiesInitializer` | Points `spring.kafka.bootstrap-servers` at the shared broker |
| `LocalStackS3Initializer` | Points `ludwig.storage.s3.*` at the shared LocalStack |
| `LocalStackS3` | An SDK client onto it, plus idempotent bucket create/clear |
| `Containers.redis()` | The shared Redis, for `cache-spring-boot-starter`'s shared tier. A `GenericContainer` and one exposed port, read from the container rather than assumed - `Containers.REDIS_PORT` is the port *inside* it |
| `@LudwigPostgresTest`, `@LudwigKafkaTest`, `@LudwigLocalStackTest` | Composed annotations, each documenting what it turns on and when not to use it |
| `DatabaseCleaner` | `TRUNCATE ... RESTART IDENTITY CASCADE` of every table but Liquibase's |
| `GreenMailFixture`, `WireMockFixture` | In-process SMTP and HTTP stubs - no image, nothing to pin |
| `TestClock` | A mutable `Clock`: `advance(Duration)`, `set(Instant)`, plus a fixed factory |

## Switching it on

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>test-support</artifactId>
    <scope>test</scope>
</dependency>
```

The version comes from `ludwig-bom`. Then either take the composed annotation:

```java
@LudwigPostgresTest
class SomethingIntegrationTest {
    // the context is already wired to a running, digest-pinned PostgreSQL
}
```

or import the configuration alongside a `@SpringBootTest` the module already has its own opinions about:

```java
@SpringBootTest(classes = TestApplication.class)
@Import(PostgresContainerConfiguration.class)
class SomethingIntegrationTest { }
```

Remember that surefire runs only what is *not* named `*IT` or `*IntegrationTest`, and failsafe runs only
what is - so a container test named otherwise never runs at all.

## The upgrade path this was really for

`images.properties` carries `# renovate:` comments beside each pin:

```properties
# renovate: datasource=docker depName=postgres
ludwig.test.image.postgres = postgres:16-alpine@sha256:cf78e766...
```

A dependency bot can raise a one-line pull request for the next digest. That is the point of putting the
values in a properties resource rather than in constants in Java source: it turns the next CVE respin
from "a twenty-one file edit somebody has to remember" into "review and merge".

`LudwigTestImages` refuses to initialise if a pin lacks an `@sha256:` segment with a 64-character hex
digest. A fixture that quietly fell back to a bare tag would reintroduce the exact policy violation this
module exists to prevent, and every suite would still be green.

## What sharing a container costs, and where that cost is paid

A container per test class gave every class an empty database by construction. One shared container does
not. That trade is made deliberately: per-test isolation becomes a **schema** concern rather than a
container concern, and `DatabaseCleaner` makes paying it one line in a `@BeforeEach`.

```java
@BeforeEach
void reset() {
    new DatabaseCleaner(dataSource).clean();
}
```

This is not merely tidiness. When what a test exercises *is* shared cluster-wide state - a lease, a
rate-limit window, an outbox row - a leftover row is not a tidy-up detail but a test that passes or fails
depending on what ran before it. `LocalStackS3.clearBucket(...)` is the S3 counterpart.

## Fork configuration, checked rather than assumed

The speedup depends on several test classes sharing a JVM, so it was verified rather than asserted:
neither the reactor root POM nor `ludwig-service-parent` sets `forkCount` or `reuseForks`, so Maven's
defaults apply - **one fork per module, reused across test classes**. The singletons are therefore
genuinely shared within a module's surefire run and within its failsafe run.

They are *not* shared across modules: each module is a separate JVM, so a full reactor build starts one
Postgres per module that needs one, not one for the whole build. A developer who sets
`testcontainers.reuse.enable=true` in `~/.testcontainers.properties` gets that too, because every
singleton is declared `withReuse(true)`; it is deliberately off by default and off in CI, since a reused
container carries the previous run's rows.

## What this module deliberately does not do

- **It depends on nothing else in this repository, and must not start.** Maven builds its reactor DAG
  from all declared dependencies regardless of scope, `test` and `optional` included. Eleven modules have
  container tests, `db-core` and `audit-core` among them; one sibling dependency here closes a cycle that
  fails the whole reactor. `architecture-rules` is the precedent this copies.
- **It is not a `test-jar`.** An ordinary jar consumed at test scope is what this repository already does,
  and it avoids the `test-jar` lifecycle entirely.
- **It ships no security fixtures.** Those need `LudwigPrincipal`, so they live in
  [`test-support-security`](../../sources/test-support-security/README.md) - see that module for why the split is not
  over-engineering.
- **It does not containerise GreenMail or WireMock.** Both are libraries, used in-process. There is no
  image, so there is nothing to pin, and the in-process versions are faster.
- **It creates no buckets and no topics.** Those names are the consuming module's vocabulary.
- **It has no enforced coverage gate**, and sets no `-Djacoco.skip`. Its classes run in eleven other
  modules' suites and in none of its own, so a gate measured here would measure the wrong thing. What it
  has instead are tests for the two things that are genuinely its own: a unit test that a digest-less pin
  is rejected, and `PostgresFixtureIntegrationTest`, which boots a context through
  `@LudwigPostgresTest` - because whether `@ServiceConnection` resolves against a digest-pinned image
  name is a fact about Spring's wiring, and shipping three composed annotations no test had ever started
  would be worse than having no coverage number.
