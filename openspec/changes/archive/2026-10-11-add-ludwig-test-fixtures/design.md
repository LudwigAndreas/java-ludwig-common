## Context

See `proposal.md` for motivation. The facts that shape the approach, each read from the repository:

- `test-support` already holds the primitives: `Containers` (one container per image per JVM,
  `withReuse(true)`), `LudwigTestImages`, `PostgresContainerConfiguration`
  (`@ServiceConnection`), `KafkaPropertiesInitializer`, `LocalStackS3Initializer`,
  `WireMockFixture`, `TestClock`, `DatabaseCleaner`. Its POM forbids any in-repo dependency, and 16
  modules depend on it.
- `WireMockFixture` deliberately does not publish its URL as a property, because "guessing which
  property a module means would be wrong more often than useful".
- `rest-client-spring-boot-starter` keys every client by name:
  `ludwig.rest-client.clients.<name>.base-url`, `@LudwigRestClient("<name>")`, and
  `RestClientRegistry.names()` at run time. `base-url` is never inherited from defaults, and the
  starter's validator refuses a credential over plain HTTP to anything but loopback.
- `architecture-rules` imports with `DO_NOT_INCLUDE_TESTS` by default and has zero in-repo
  dependencies.
- `scripts/project_index.py` gives the role `test-support` to any artifactId starting with
  `test-support`; that role belongs in `sources/`.
- `crud-service-example`'s `local` profile disables Kafka and the outbox poller.
- The reactor is on Spring Boot 3.3.5, which has `SpringApplication.from(...).with(...)` and
  `spring-boot:test-run`.

## Goals / Non-Goals

**Goals:**

- One module a service adds at test scope to get a runnable, debuggable local application.
- One stub definition and one fixture definition, used by both integration tests and the launcher.
- Nothing in the module is active unless a test or a local application asks for it.
- The module is proven by its own integration tests, with no service consuming it yet.

**Non-Goals:**

- Anything listed under Non-goals in `proposal.md`.
- A new abstraction over WireMock's stubbing DSL. Stubs are written in WireMock's own API.
- A custom HTTP control endpoint. No controller, no new path.
- Inferring which backing services a service needs from its classpath.

## Decisions

### D1. Module identity: `test-support-local`, library tier, in `sources/`

ArtifactId `test-support-local`, parent `common` with `<relativePath>../../pom.xml</relativePath>`,
imports `ludwig-bom`, plain `jar` packaging, package root `ru.ludwigandreas.testsupport.local`.

*Why this name and not `ludwig-test-fixtures`:* the role is assigned by artifactId prefix, and
`test-support-security` is the existing precedent for "a test-support module that needs something
`test-support` may not depend on". `ludwig-test-fixtures` would be classified `library`.
*Alternative considered:* adding the classes to `test-support`. Rejected: the module needs
`rest-client-spring-boot-starter`, and `test-support` must depend on nothing in this repository.

### D2. Dependency-direction check

The change adds two in-repo edges from `test-support-local`.

- **Does `test-support` already depend on `test-support-local`, directly or transitively?**
  `test-support` has zero in-repo dependencies (`scripts/manifest.sh module sources/test-support`:
  "depends on (0)"). No cycle.
- **Does `rest-client-spring-boot-starter` already depend on `test-support-local`, directly or
  transitively?** Its in-repo dependencies are `audit-core`, `observability-spring-boot-starter`
  and `web-core-spring-boot-starter`; `test-support-local` is new and nothing depends on it. No
  cycle.

The inverse constraint this creates: a module that `rest-client-spring-boot-starter` depends on
(today `audit-core`, `observability-spring-boot-starter`, `web-core-spring-boot-starter`, and the
starter itself) can never take `test-support-local` as a dependency at any scope. The reactor
refuses to build if one does, which is the check; the module POM carries a comment saying so.

The `rest-client-spring-boot-starter` edge is `optional`. A service with no rest client gets the
launcher and fixtures without the starter, and the stub wiring is guarded by a class-presence
condition. Kafka (`spring-kafka`, `org.testcontainers:kafka`), LocalStack and the AWS SDK are
`optional` third-party dependencies for the same reason `test-support` makes them so.

### D3. Which POMs change

- **`build/ludwig-bom/pom.xml`**: one `<dependency>` entry for `test-support-local` at
  `${project.version}`, beside `test-support-security`. No third-party version is added or moved.
- **`build/ludwig-service-parent/pom.xml`**: one `bannedDependencies` rule inside the existing
  `enforce-platform-rules` execution (D10).
- **`pom.xml` (root)**: one `<module>sources/test-support-local</module>` entry, after
  `sources/rest-client-spring-boot-starter` and `sources/test-support`. No plugin or build
  configuration changes.
- **`build/jacoco-aggregate/pom.xml`**: one dependency entry, as `repository-layout` requires of
  every jar module. This is not one of the three structural POMs.

### D4. The launcher is a thin builder over `SpringApplication.from`

```java
public final class LocalCatalogApplication {            // src/test/java/.../local/
    public static void main(String[] args) {
        LocalRun.from(CatalogApplication::main)
                .postgres()
                .kafka()
                .stubs(new CatalogStubs())
                .fixtures(CatalogFixtures.class)
                .profiles("local")
                .run(args);
    }
}
```

`LocalRun` collects configuration classes and initializers and hands them to
`SpringApplication.from(main).with(...)`. Backing services are named explicitly, one method each,
delegating to `test-support`'s existing configuration and initializers.

*Why explicit and not inferred from the classpath:* a service with `spring-kafka` on its classpath
and Kafka disabled by configuration is ordinary here (`ludwig.identity.kafka.enabled=false`), so
the classpath does not say what the service needs.
*Why no default profile:* the only `local` profile that exists disables what this launcher turns
on. Which profile a service's local application wants is that service's decision, in the next
change.
*Alternative considered:* `spring-boot-docker-compose`. Rejected: a compose file is a second place
an image reference lives, and `container-image-pinning` wants one.

### D5. Stubs are plain objects started before the context, not beans

A `PartnerStub` declares its client name and registers its healthy answers on a WireMock server
the module owns. Stubs are grouped in a `StubCatalog` the service writes once. The launcher and
the JUnit extension both take the catalog, start one server per stub, and add a property source of
`ludwig.rest-client.clients.<name>.base-url` at highest precedence before the context refreshes.

*Why not beans:* the rest-client starter binds and validates `base-url` during context start-up. A
stub that is a bean starts too late to supply it.
*Why one server per stub and not one server with a path prefix per client:* "one partner is down,
the rest are fine" is then a property of one server, and a client's `base-url` stays a host root as
in production.
*Why this is no longer the guess `WireMockFixture` refuses to make:* the stub states its client
name, and the name maps to exactly one property.

`WireMockFixture` in `test-support` is unchanged and stays the right tool for a test that wants a
bare server.

### D6. Faults are WireMock scenario states

Each stub's server gets, besides its healthy mappings, one high-priority catch-all mapping per
fault, each requiring a state of a scenario named `fault`. Setting a fault is setting that
scenario's state; clearing it is returning the scenario to its start state.

| Fault | Realised as |
|---|---|
| `unavailable` | 503 |
| `slow` | fixed delay, `ludwig.local.stubs.<name>.slow-delay`, default 30s |
| `reset` | WireMock `CONNECTION_RESET_BY_PEER` |
| `empty` | WireMock `EMPTY_RESPONSE` |
| `refused` | the listener is stopped; restarting it on the same port clears the fault |

The three control surfaces are then one mechanism: the Java API sets the scenario state, the
start-up property sets it once after start, and over HTTP it is WireMock's own admin call
(`PUT /__admin/scenarios/fault/state`) on the stub's port. The start-up summary prints that call
ready to paste for each stub. No controller is written.

*Consequences accepted:* `refused` closes the admin interface with the listener, so it is not
switchable over HTTP (specified). A path-scoped fault is set from the Java API; over HTTP it is a
hand-written admin mapping, documented in the README but not wrapped.
*Reset between tests* restores healthy mappings and the fault scenario's start state and clears
recorded requests. It must not be WireMock's `resetAll`, which would delete the fault mappings.

### D7. Client-implies-stub is checked twice, in two places that see different things

- **Build time:** `LocalRunRulesTest`, an abstract JUnit class in the module's main sources. A
  service subclasses it under `architecture` and names its `StubCatalog`. It imports main and test
  classes with ArchUnit, collects every interface annotated `@LudwigRestClient`, and fails for any
  whose name has neither a stub in the catalog nor a pass-through declaration. It also fails when a
  class calling `LocalRun` is outside a package named `local`.
- **Start-up:** the launcher compares `RestClientRegistry.names()` with the catalog and the
  pass-through declarations, in both directions, and refuses to start on a mismatch.

Pass-through is declared in the catalog, so ArchUnit can read it. Separately,
`ludwig.local.stubs.<name>.mode=passthrough` switches an existing stub off for one launch; that
needs no build-time counterpart because the stub still exists.

*Why not a `RuleGroup` in `architecture-rules`:* that harness excludes test classes and cannot
depend on the rest-client starter or on this module. `SqlConfinementTest` and the in-app channel's
service-local rules are the precedent for an ArchUnit rule that lives with the thing it protects.

### D8. Fixtures are beans of the launched context

A `SeedFixture` is a Spring component in the service's test sources; `LocalRun.fixtures(...)`
registers its configuration. A runner orders fixtures by their declared predecessors, applies the
mode, and runs each in its own transaction when a transaction manager exists. It runs as an
application runner, which is after Liquibase and before the summary is logged.

- `reset` uses `test-support`'s `DatabaseCleaner`.
- `keep` treats "any non-migration table has a row" as "already seeded".
- From a test, the same runner is injected and called.

*Why beans here when stubs are not:* a fixture needs the service's repositories, which exist only
inside the context; a stub must exist before it.
*Why not SQL seed files:* a file keeps seeding a column that was renamed last month; a fixture
written against the entity stops compiling.

Kafka helpers take a `KafkaOperations` and work on `ProducerRecord`, so they need no dependency on
`messaging-spring-boot-starter`. A fixture that publishes declares that it needs Kafka, and the
runner fails at start-up when Kafka was not named.

### D9. The module proves itself with a sample application in its own tests

`src/test/java/ru/ludwigandreas/testsupport/local/integration` holds a minimal service: one
`@LudwigRestClient`, one table, one topic, one stub catalog, two fixtures. Integration tests start
it through `LocalRun` and through the JUnit extension and assert the scenarios in the three new
specs. `test-support` set this precedent with `PostgresFixtureIntegrationTest`.

The sample's table is created by a changelog that satisfies `scripts/check_migrations.sh`.

### D10. Conventions introduced, and the check for each

| Convention | Check | Owner |
|---|---|---|
| Every rest client has a stub or a pass-through | `LocalRunRulesTest` (ArchUnit) and the launcher's start-up check | ArchUnit, shipped by this module; behaviour test |
| A local application lives in a `local` package under `src/test` | `LocalRunRulesTest`; and the module being test scope makes `src/main` use a compile error | ArchUnit; the compiler |
| No `test-support*` artifact at compile or runtime scope in a service | `bannedDependencies` in `ludwig-service-parent`'s `enforce-platform-rules` | Maven enforcer |
| Stubs bind loopback only | integration test in the module | behaviour test |
| The module activates nothing by itself | integration test booting a plain context with the module on the classpath; the module registers no auto-configuration | behaviour test |
| A module `rest-client-spring-boot-starter` depends on must not depend on this one | the reactor fails on the cycle; comment in the module POM | Maven |
| `LudwigTestImages` is the only image source | `scripts/check_image_pins.sh`, already in the gate | existing gate script |
| A fixture is code, not a SQL script | **No check is possible**: nothing distinguishes a seed script from any other test resource. Recorded as a comment on `SeedFixture` and in the README | comment |

### D11. Nothing here is a second implementation of a centralised mechanism

The module adds no audit sink, no operation envelope, no cache, no `ProblemDetail` handling, no
redaction mask and no endpoint. Container images, the cleaner and the clock are reused from
`test-support`, not restated. The start-up summary goes through SLF4J, never the console.

## Risks / Trade-offs

- **A stub is a belief about a partner, and nothing fails when the partner changes.** → Not solved
  here. The README says so in its first section, and the pass-through mode exists so a developer
  can point one client at a real environment. A contract-drift check is future work.
- **Property-source ordering.** If the stub's `base-url` does not win over `application.yml`, the
  service calls a real host from a laptop. → Task 3.2 proves precedence in an integration test
  whose sample `application.yml` names an unroutable remote URL; this is the first thing built
  after the module skeleton, and the change stops there if it cannot be made to hold.
- **`SpringApplication.from` with a `main` that sets its own additional profiles or sources.** →
  The sample application covers the plain case only. A service whose `main` does more is found in
  the next change, not this one.
- **A fixture that needs an authenticated caller.** Entities with audit columns may require a
  principal while seeding. → The module does not depend on `test-support-security`; a fixture that
  needs a principal uses that module itself. Documented, not solved.
- **A client with token-based authentication.** Overriding `base-url` alone leaves the token URL
  pointing at production. → A stub can contribute extra properties (specified); the README shows
  the token-endpoint case.
- **Reused containers.** With `testcontainers.reuse.enable=true` the database survives a restart.
  → The default fixture mode is `reset`.
- **The enforcer rule changes `ludwig-service-parent`.** A mistake there breaks both services. →
  The spec requires both services to pass unchanged, and the gate is the full reactor.
- **Unused surface.** No service consumes the module in this change, so its API is designed
  against one sample. → Accepted: the next change is expected to adjust it, and the README marks the
  API provisional until a service consumes it.

## Open Questions

- Whether `LocalRun` should also offer a movable `Clock` bean for services that already inject
  `Clock`. It does not affect anything specified here and is easier to judge once the clock-seam
  change is scoped.

## Implementation notes

Where the implementation departs from the decisions above. Each is also in `state.json`.

- **D4, the launcher's mechanism.** `LocalRun` calls `SpringApplication.withHook` directly, not
  `SpringApplication.from(...).with(...)`. `Augmented` can add configuration classes and profiles
  but not an `ApplicationContextInitializer`, and D5 requires the stubs to be one. `withHook` is
  what `from` is built on, and `spring-boot:test-run` works unchanged.
- **D4, PostgreSQL wiring.** `LocalRun.postgres()` does not register `test-support`'s
  `PostgresContainerConfiguration`. That class exposes the shared container as a
  `@ServiceConnection` bean, so a closing context stops the per-JVM singleton unless Testcontainers
  reuse is enabled, and every other context in the JVM loses its database. The launcher registers
  `JdbcConnectionDetails` and `LiquibaseConnectionDetails` beans read from `Containers.postgres()`
  instead. The image and the singleton are still `test-support`'s.
- **D6, clearing a fault over HTTP.** It is `PUT /__admin/scenarios/fault/state` with no body.
  Naming the start state is rejected by WireMock (422), because no mapping references it.
- **D7, the "stub for a client that does not exist" direction.** It cannot be read from
  `RestClientRegistry`: a stub publishes `base-url` for its name, which makes the registry report
  that name. It is checked against the clients configured by every property source except the
  stubs' own.
- **D9, test naming.** The module's integration tests are named `*IT` and it binds failsafe, as
  `job-core` and `messaging-spring-boot-starter` do. In library modules surefire has no exclusions
  and failsafe matches only `*IT`, so a `*IntegrationTest` there runs at `test`.
- **Visibility.** `LocalRunConfiguration` and `LocalRunPlan` are public, so the configuration can be
  started without the launcher in the test that proves it starts without the rest-client starter.
