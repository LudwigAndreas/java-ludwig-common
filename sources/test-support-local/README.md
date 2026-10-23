# test-support-local

***English** · [Русский](README.ru.md)*

Starts a service's real entry point on your own machine, with the backing services it names, an HTTP
stand-in for each of its partners, and seed data - so that debugging any code path is "run it, set a
breakpoint". The same stubs and fixtures serve the service's integration tests. Added as a single
test-scoped dependency.

| | |
|---|---|
| Role | test support (test-scoped jar) |
| Depends on (in-repo) | [`rest-client-spring-boot-starter`](../rest-client-spring-boot-starter), [`test-support`](../test-support) |
| Used by | `notification-service` |
| Surfaces | none (starts HTTP stubs and a mail server locally) |

**The API is provisional.** One service uses it so far. For a complete, working setup to copy, read
[`notification-service`'s "Running locally"](../../services/notification-service/README.md#running-locally):
a local application, three stand-ins, seed fixtures, the rules test, and a test that launches it.

## A stub is a belief about a partner

Read this before anything else. A partner stub answers the way *you* think the partner answers.
Nothing in this module fails when the partner changes its contract and the stub does not, and a
service that passes every local run against its stubs can still be wrong against the real thing.

Two things follow. Keep stubs small and obviously canned, so nobody mistakes them for a
specification. And when the question is "what does the partner really do", point that one client at
a real environment instead: declare it pass-through (below) and it keeps the address its
configuration names.

## Why

The code under the breakpoint should be the code production runs: the real `main`, Kafka on, the outbox poller
running, and every partner answering. This module composes [`test-support`](../test-support)'s pinned containers
and stub server into a runnable service, and keeps partner stubs in one catalogue that both the local run and the
integration tests use.

## What you get

| Type | What it is |
|---|---|
| `LocalRun` | Starts the service's own `main` with the backing services, stubs and fixtures it names |
| `BackingService` | PostgreSQL, Kafka, LocalStack, Redis - `test-support`'s digest-pinned singletons, started only when named |
| `PartnerStub`, `StubCatalog`, `StubAddress` | One HTTP stand-in per partner, where its address is published, and the one list of them a service keeps |
| `LocalMailbox` | An in-process mail server, and what it received |
| `StubServers`, `RunningStub` | The started stubs: address, recorded requests, fault switch |
| `StubFault` | `unavailable`, `slow`, `reset`, `empty`, `refused` |
| `@WithPartnerStubs` | The same stubs for an integration test, reset after every test method |
| `SeedFixture`, `SeedFixtures`, `FixtureMode` | Ordered seed data, with `reset` / `keep` / `none` |
| `KafkaDeliveries` | Publishes seed records, and delivers one record more than once |
| `LocalRunRulesTest` | The build-time rule: every rest client has a stub or a pass-through |

## Switching it on

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>test-support-local</artifactId>
    <scope>test</scope>
</dependency>
```

The version comes from `ludwig-bom`. `<scope>test</scope>` is not optional: `ludwig-service-parent`
fails a service that has any `test-support` artifact at compile or runtime scope, because a stand-in
must never be able to answer for a partner in a deployed image.

Declare what you use beside it, at test scope. Each is `optional` here, for the reason `test-support`
gives: a service that uses one part should not acquire the others' dependency trees.

| You use | Declare |
|---|---|
| Partner stubs | `org.wiremock:wiremock-standalone` |
| `LocalRunRulesTest` | `com.tngtech.archunit:archunit` |
| `.kafka()`, `KafkaDeliveries` | `org.testcontainers:kafka`, and `spring-kafka` if the service does not already have it |
| `.localstack()` | `org.testcontainers:localstack` |
| `.mail()` | `com.icegreen:greenmail-junit5` |

## The local application

A class with a `main`, in the service's **test** sources, in a package named `local`:

```java
// src/test/java/ru/ludwigandreas/example/catalog/local/LocalCatalogApplication.java
public final class LocalCatalogApplication {

    public static void main(String[] args) {
        LocalRun.from(CatalogApplication::main)
                .postgres()
                .kafka()
                .mail()
                .stubs(new CatalogStubs())
                .fixtures(SupplierFixture.class, ProductFixture.class)
                .run(args);
    }
}
```

Run it from the IDE, or with
`mvn -pl :<service> spring-boot:test-run -Dspring-boot.run.main-class=<the local application>`. The
property is needed because the plugin otherwise starts the service's own main class. When it is up
it logs what it started and where:

```text
Local run is ready.
  Backing services
    postgres      jdbc:postgresql://localhost:54021/test
  Partner stubs
    suppliers     http://127.0.0.1:54033   admin http://127.0.0.1:54033/__admin   fault none
        set a fault:  curl -X PUT http://127.0.0.1:54033/__admin/scenarios/fault/state -H 'Content-Type: application/json' -d '{"state":"unavailable"}'   (unavailable | slow | reset | empty)
        clear it:     curl -X PUT http://127.0.0.1:54033/__admin/scenarios/fault/state
  Seed fixtures (mode reset)
    SupplierFixture, ProductFixture
```

Three decisions are worth knowing:

- **It starts the service's own `main`.** The application class is not replaced or re-declared, so
  the bean graph under the breakpoint is the one production builds, plus the local additions.
- **Nothing is inferred.** A backing service starts only when it is named. A service with
  `spring-kafka` on its classpath and its consumers switched off by configuration is ordinary on this
  platform, so the classpath does not say what a service needs.
- **No profile is imposed.** Add `.profiles("local")` if you want one. The launcher activates none,
  because an existing `local` profile may switch off the very things the launcher turns on.

**`spring-boot:test-run` is not the test classpath of the build.** It runs with what the Spring Boot
plugin would package, plus test classes: a `provided` dependency the plugin `<excludes>` is absent.
Anything the local application uses - a fixture, a stub - has to load without it. Start the
application that way once before calling the setup done; a failsafe test that launches it will not
tell you.

### Mail

`.mail()` starts an in-process mail server on loopback and points `spring.mail.host` and
`spring.mail.port` at it. Nothing leaves the machine. A test reads `LocalMailbox.received()`; a
developer points a mail client at the IMAP address in the start-up summary and logs in as the
recipient's address with any password.

### Adding to the summary

A bean that implements `SummaryContributor` gets a section of its own in the start-up summary - a
ready-made command for calling the service, say. A stub adds lines under its own entry by
overriding `summaryLines`.

## Partner stubs

A stub is a plain class - not a Spring bean - that names its rest client and registers the partner's
healthy answers in WireMock's own API:

```java
public class SupplierDirectoryStub implements PartnerStub {

    @Override
    public String name() {
        return "suppliers";          // as in @LudwigRestClient("suppliers")
    }

    @Override
    public void register(WireMockServer server) {
        server.stubFor(get(urlPathEqualTo("/api/v1/suppliers"))
                .willReturn(okJson("[{\"id\":\"acme\",\"name\":\"ACME GmbH\"}]")));
    }
}
```

and the service lists its stubs once:

```java
public class CatalogStubs implements StubCatalog {

    @Override
    public List<PartnerStub> stubs() {
        return List.of(new SupplierDirectoryStub(), new NotificationStub());
    }

    @Override
    public Set<String> passThrough() {
        return Set.of("billing");    // deliberately calls its configured address
    }
}
```

The module starts one server per stub **before** the context refreshes and sets
`ludwig.rest-client.clients.<name>.base-url` ahead of every other property source. The real client
then calls the stub, so its pool, timeouts, retry, circuit breaker and error translation all run. The
service's own `application.yml` naming a production host does not win; that is tested, because the
alternative is a laptop calling production.

### A partner that is not a rest client

A stub's name is, by default, the rest client it stands in for. A partner the service reaches some
other way overrides `address()`:

| `StubAddress` | The stub's URL is published as | For |
|---|---|---|
| `restClient()` (default) | `ludwig.rest-client.clients.<name>.base-url` | a `@LudwigRestClient` |
| `property("...")` | the named property | a client the service builds itself from a property of its own |
| `none()` | nothing | a partner whose address is data, such as a webhook receiver; a fixture or test reads `StubServers.stub(name).baseUrl()` |

The launcher refuses a stub whose property no other source configures - what a renamed client or
property leaves behind. An empty value counts as not configured, so a service whose own default is
empty gives the property a placeholder in its local configuration.

Every stub listens on `127.0.0.1` only. Leave mapping priority `1` alone - the fault mappings use it.
Body and mapping files for a stub, if you want them, go under
`src/test/resources/ludwig-local-stubs/<client name>/`.

Per-stub configuration, all under `ludwig.local.stubs.<stub name>`:

| Property | Meaning |
|---|---|
| `.port` | A fixed port. Unset, the operating system allocates one |
| `.fault` | A fault in force from start-up |
| `.slow-delay` | How long `slow` waits. Default `30s` |
| `.mode` | `stub` (default) or `passthrough` - the second skips the stub for this one launch |

### In an integration test

```java
@SpringBootTest
@WithPartnerStubs(CatalogStubs.class)
class SupplierOutageIT {

    @Autowired
    StubServers stubs;

    @Test
    void degradesWhenTheDirectoryIsDown() {
        stubs.stub("suppliers").fail(StubFault.UNAVAILABLE);
        // ... call the service, assert on the degradation
    }
}
```

After every test method each stub is back to healthy answers, no fault and an empty request journal.

### A client that fetches a token

Overriding `base-url` alone leaves the token URL pointing at the real identity provider. A client
with `auth.registration-id` takes that URL from its Spring Security client registration, so let the
stub answer for the token endpoint too and return that property from `properties(baseUrl)`:

```java
@Override
public Map<String, String> properties(String baseUrl) {
    return Map.of("spring.security.oauth2.client.provider.suppliers.token-uri", baseUrl + "/oauth/token");
}
```

## Faults

| Fault | What the client sees | Set over HTTP with state |
|---|---|---|
| `unavailable` | HTTP 503 | `unavailable` |
| `slow` | HTTP 503 after `slow-delay` - for exceeding a read timeout | `slow` |
| `reset` | The connection reset before any response | `reset` |
| `empty` | The connection closed with no response | `empty` |
| `refused` | Nothing listening on the port | *not available over HTTP* |

A fault is one switch reachable from three places:

- **Test code:** `stub.fail(StubFault.SLOW)`, `stub.fail(StubFault.RESET, "/api/v1/suppliers/.*")` for
  one path, `stub.heal()`.
- **At start-up:** `--ludwig.local.stubs.suppliers.fault=unavailable`.
- **While paused at a breakpoint:** the `curl` line the start-up summary prints for that stub. It is
  WireMock's own admin API - this module adds no endpoint - and a `PUT` with no body clears it.

`refused` stops the stub's listener, which takes the admin API with it, so it cannot be set over HTTP
or for a single path. A path-scoped fault over HTTP is a hand-written WireMock mapping at priority `1`
posted to `/__admin/mappings`.

One stub's fault never touches another, which is what a partial outage is: one partner down, the rest
answering.

## Seed fixtures

A fixture writes through the service's own repositories or services, which it gets by constructor
injection:

```java
public class ProductFixture implements SeedFixture {

    private final ProductRepository products;

    public ProductFixture(ProductRepository products) {
        this.products = products;
    }

    @Override
    public void seed() {
        products.save(new ProductEntity("SKU-1", "acme"));
    }

    @Override
    public List<Class<? extends SeedFixture>> after() {
        return List.of(SupplierFixture.class);
    }
}
```

Fixtures run after the service's migrations, in an order that satisfies every `after()`, each in a
transaction of its own. A fixture that throws rolls back its own writes, stops the launch and is
named in the error; a cycle fails start-up naming its members.

**Leave fixtures without `@Component`.** A stereotype under the service's base package is picked up
by every `@SpringBootTest` the service has. Name them on `LocalRun.fixtures(...)`, or in a test:

```java
@SpringBootTest
@Import({SeedFixtureConfiguration.class, SupplierFixture.class, ProductFixture.class})
class CatalogueIT {

    @Autowired
    SeedFixtures fixtures;

    @BeforeEach
    void seed() {
        fixtures.reset();
    }
}
```

`ludwig.local.fixtures.mode` decides what a launch does with data that is already there - which, with
Testcontainers reuse enabled, is yesterday's:

| Mode | Behaviour |
|---|---|
| `reset` (default) | Empty every table except Liquibase's, then seed. Every launch starts equal |
| `keep` | Seed only when the database holds no rows; otherwise touch nothing |
| `none` | Neither empty nor seed |

**A fixture is code, not a SQL script.** A seed file keeps inserting a column that was renamed last
month; a fixture written against the entity stops compiling. No check enforces this, and none is
possible - nothing distinguishes a seed script from any other test resource - so it is recorded here
and on `SeedFixture`.

**A fixture that needs an authenticated caller** - an entity with audit columns, say - builds its
principal with [`test-support-security`](../test-support-security/README.md). This module does not
depend on it.

### Messages

```java
KafkaDeliveries.publish(kafkaTemplate, List.of(record1, record2));   // seed, awaited
KafkaDeliveries.duplicate(kafkaTemplate, record);                    // the same record, twice
KafkaDeliveries.deliver(kafkaTemplate, record, 5);
```

The copies share key, headers and payload and differ only in offset, which is what a redelivery after
a lost commit looks like to a listener. A fixture that publishes overrides `needsKafka()`, and the
local application then refuses to start unless it named Kafka.

## The two rules

**Every rest client has a stub or a pass-through.** A service with a local application adds:

```java
class LocalRunArchitectureTest extends LocalRunRulesTest {

    @Override
    protected String basePackage() {
        return "ru.ludwigandreas.example.catalog";
    }

    @Override
    protected StubCatalog catalog() {
        return new CatalogStubs();
    }
}
```

A new `@LudwigRestClient` interface with no stand-in then fails the build, naming the interface. The
same test fails anything that uses `LocalRun` outside a package named `local`. The launcher checks
again at start-up, against the rest-client registry - which also sees a client that is only a
configured name - and in the other direction: a stub for a client that no longer exists stops the
launch too.

The rule is shipped here and not as a `RuleGroup` in `architecture-rules` because that harness
excludes test classes and has no in-repo dependencies, so it can see neither a stub nor `LocalRun`.

**No `test-support` artifact outside test scope.** A Maven enforcer rule in `ludwig-service-parent`.

## What this module deliberately does not do

- **It registers no auto-configuration.** On a test classpath, with neither `LocalRun` nor
  `@WithPartnerStubs` in use, it starts nothing and overrides nothing. `InertOnClasspathIT` holds it
  to that.
- **It adds no endpoint.** The control surface for a stub is WireMock's admin API.
- **It declares no image.** Containers come from `test-support`'s `LudwigTestImages` and nowhere else.
- **It does not use `PostgresContainerConfiguration` for a launched application.** That exposes the
  shared container as a bean, and closing the context then stops the container for every other
  context in the JVM unless Testcontainers reuse is on. `LocalRun` publishes connection details
  instead.
- **It ships no stateful fake.** A stub may keep state if it has to; none here does.
- **It provides no clock and no ID seam.** A movable clock needs the service to inject `Clock`
  everywhere, and deterministic IDs need an abstraction the platform does not have. Both are
  production-code changes.
- **It does not simulate a broker or database outage.** That needs a proxy container, which is another
  pinned image.
- **It has no "table used implies fixture defined" rule.** Designed against one sample it would be
  wrong; it waits for two real services.
- **`test-support`, and anything `rest-client-spring-boot-starter` depends on, may never depend on
  it.** Maven builds its reactor graph from every scope, so that edge closes a cycle. See the POM.

## Deployment, observability, audit

Not applicable: test scope only, never in a deployed artifact.

## Runbook

| Symptom | Likely cause | What to do |
|---|---|---|
| `LocalRunRulesTest` fails naming a rest client | A `@LudwigRestClient` has neither a stub nor a pass-through, or a stub names a client that no longer exists | Add a `PartnerStub` to the catalogue, declare it pass-through, or remove the stale stub |
| Local run cannot start a backing service | Docker not running | Start Docker |
| A partner behaves differently in production | Stubs are beliefs, not contracts | Point that one client at a real environment (pass-through) and update the stub |
| Seed data missing or duplicated | `FixtureMode` | `reset` re-seeds, `keep` keeps existing rows, `none` seeds nothing |
