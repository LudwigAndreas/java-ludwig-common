## Purpose

Lets a developer start a service's real entry point on their own machine with the backing
services, partner stand-ins and seed data it needs, so that any code path can be debugged with a
breakpoint without a deployed environment.

## ADDED Requirements

### Requirement: A local application starts the service's real entry point
A local application SHALL start the service through the service's own `main` method and SHALL NOT
replace, subclass or re-declare the service's application class, so that the bean graph under a
breakpoint is the one production builds.

#### Scenario: The local application is started from an IDE
- **WHEN** a developer runs a service's local application
- **THEN** the service answers on its HTTP port with every bean its production entry point
  declares, plus the local additions

#### Scenario: The local application is started from Maven
- **WHEN** a developer runs `mvn -pl :<service> spring-boot:test-run` with
  `-Dspring-boot.run.main-class` naming the local application
- **THEN** the same application starts, with no Docker Compose file and no manual container start

### Requirement: Backing services are started only when named
The local application SHALL start a backing service only when it names it. PostgreSQL, Kafka,
LocalStack and Redis SHALL each be available, SHALL come from `test-support`'s digest-pinned
singletons, and SHALL be connected to the service without the developer supplying a URL.

#### Scenario: A service names PostgreSQL only
- **WHEN** a local application names PostgreSQL and nothing else
- **THEN** one PostgreSQL container is started, the service's migrations run against it, and no
  Kafka, LocalStack or Redis container is started

#### Scenario: A service names Kafka
- **WHEN** a local application names Kafka
- **THEN** `spring.kafka.bootstrap-servers` resolves to the started broker and the service's
  listeners connect to it

#### Scenario: A backing service is named but its client library is absent
- **WHEN** a local application names a backing service whose Testcontainers module is not on the
  test classpath
- **THEN** start-up fails with a message naming the missing dependency, before any container is
  started

### Requirement: The launcher reports what it started
After start-up the local application SHALL log one summary listing every backing service with its
connection address, every partner stub with its client name, base URL, control URL and active
fault, every pass-through client with the address it calls, and the fixture mode with the fixtures
that ran.

#### Scenario: A developer reads the start-up log
- **WHEN** the local application has started with PostgreSQL and two stubs
- **THEN** the log contains the JDBC URL, both stub base URLs and both control URLs, so the
  developer can connect a database client or switch a fault without reading source

### Requirement: Local stand-ins listen on loopback only
Every HTTP stand-in the launcher starts SHALL bind to the loopback interface, so a debugging
session never exposes a stub or its control interface to the network.

#### Scenario: A stub is reached from another host
- **WHEN** a request is sent to a stub's port on the machine's non-loopback address
- **THEN** the connection is refused

### Requirement: Local-run code cannot ship
The module SHALL be consumable at test scope only. A service SHALL NOT have any `test-support`
artifact on its compile or runtime classpath, and a service's local application SHALL live under
`src/test`, in a package named `local`.

#### Scenario: A service declares the module without test scope
- **WHEN** a service POM declares `test-support-local` with compile or runtime scope
- **THEN** the service's build fails naming the artifact and the scope, before any test runs

#### Scenario: A local application is placed in main sources
- **WHEN** a class using the launcher is compiled from `src/main`
- **THEN** compilation fails, because the module is not on the main classpath

#### Scenario: Nothing activates without a local application
- **WHEN** the module is on a test classpath and an ordinary `@SpringBootTest` runs without using
  the launcher or the stub extension
- **THEN** no container is started, no stub is started and no property is overridden by the module

### Requirement: The launcher imposes no profile
The launcher SHALL activate no Spring profile of its own. A local application names the profiles
it wants.

#### Scenario: A service has an existing `local` profile that disables Kafka
- **WHEN** a local application names Kafka and does not name the `local` profile
- **THEN** the existing profile's properties are not applied and Kafka is enabled
