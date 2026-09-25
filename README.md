# ludwig-common

> A modular Java 17 library for common parts 

## 📦 Overview

This project is a personal Java library built with **Java 17** and **Maven**, organized as a **multi-module Maven project**.  
Each module provides a focused set of utilities or functionality and can be reused independently in various Java projects.

## 📁 Project Structure

```text
ludwig-common/
│
├── .mvn/maven.config        # -Drevision=… — the ONLY place the version is declared
├── pom.xml                  # Reactor root: modules, build config, scm, distributionManagement.
│                            #   Parent of the LIBRARY modules only.
├── Jenkinsfile              # How the platform ships: build, verify, deploy, push images
│
├── ludwig-bom/              # Published: every version, and nothing else
│ └── pom.xml
├── ludwig-service-parent/   # Published: every build decision. What a microservice inherits
│ └── pom.xml
│
├── db-core/                 # Library modules — parented by the reactor root,
│ └── pom.xml                #   each importing ludwig-bom for versions
├── job-core/
│ └── pom.xml
├── <other starters>/
│ └── pom.xml
│
├── crud-service-example/    # Services — parented by ludwig-service-parent,
│ └── pom.xml                #   shipped as container images
├── notification-service/
│ └── pom.xml
│
└── README.md
```

The split matters: a **library** keeps the reactor root as its parent so it stays usable by a
service running a different Spring Boot line, while a **service** inherits `ludwig-service-parent`
and with it a fixed Boot baseline. See [Consuming the platform](#consuming-the-platform).

Each module has:
- Its own `pom.xml` and dependencies
- A well-defined and isolated purpose
- No unnecessary coupling with other modules (unless explicitly required)

## 📚 Modules

| Module Name    | Description                                     |
|----------------|-------------------------------------------------|
| [`ludwig-bom`](ludwig-bom/README.md) ([ru](ludwig-bom/README.ru.md)) | The platform's version registry: every `ru.ludwigandreas` module plus every third-party version pinned on top of Spring Boot. Import it (`type=pom`, `scope=import`) and name no versions. Published with no parent, so a consumer never has to resolve anything else to use it |
| [`ludwig-service-parent`](ludwig-service-parent/README.md) ([ru](ludwig-service-parent/README.ru.md)) | The POM every microservice inherits: Spring Boot's build wiring, the compiler and its annotation processors in the order Lombok/MapStruct/QueryDSL require, surefire + failsafe, an enforced coverage gate, the enforcer gate, Checkstyle, and a fully configured jib that never runs unless asked. Imports `ludwig-bom`, so one `<parent>` covers versions too |
| [`odata-filter-spring-boot-starter`](odata-filter-spring-boot-starter/README.md) ([ru](odata-filter-spring-boot-starter/README.ru.md)) | Enterprise-ready OData `$filter`/`$top`/`$skip`/`$orderby` support for Spring Boot + Spring Data JPA REST APIs |
| [`db-core`](db-core/README.md) ([ru](db-core/README.ru.md)) | Base entity classes, auditing, soft delete, exceptions and QueryDSL/Spring Data JPA utilities for Java 17 + Postgres + Spring Boot services |
| [`job-core`](job-core/README.md) ([ru](job-core/README.ru.md)) | The mechanics every scheduled, database-backed worker in this platform is built from, in one place so that there is one of each rather than one per module: exponential backoff with jitter and a cap, a `SmartLifecycle` scheduling base that owns its own `TaskScheduler`, refuses to run twice at once and drains on shutdown instead of abandoning claimed rows, the `FOR UPDATE SKIP LOCKED` claim statement, a stable instance identity for lock-owner columns, and the platform's one leased, `run_id`-fenced distributed lock - it survives the death of the process holding it, takes a callback or a handle, carries a configurable default lease, and publishes acquisition and lost-lease counters through an optional Micrometer binding kept off its own dependency path |
| [`outbox-spring-boot-starter`](outbox-spring-boot-starter/README.md) ([ru](outbox-spring-boot-starter/README.ru.md)) | Enterprise-ready transactional outbox for Java 17 + Postgres + Spring Boot: `FOR UPDATE SKIP LOCKED` polling, retry/backoff, dead-letter handling, ordering, idempotency, Kafka/REST dispatch with multi-destination routing, metrics and audit |
| [`reconciliation-spring-boot-starter`](reconciliation-spring-boot-starter/README.md) ([ru](reconciliation-spring-boot-starter/README.ru.md)) | The inbound twin of the transactional outbox: keeps local records that mirror externally owned state in sync, so a service author writes a demand query, a fetcher and a reconciler instead of another hand-rolled `@Scheduled` polling loop. Four fetch shapes - per item, batched, paged with cursor checkpointing, and submit/poll/collect against an asynchronous remote job - reduced to one internal stream of key-addressed external records; two-phase staging so a retry never re-calls the partner; stale-write protection, payload-hash idempotency, per-item transactions, retry with backoff and quarantine; partner-scoped distributed quotas and rate limits held as heartbeated leases; leader election, watermarks and priority tiers; a freshness-lag SLO metric, an audit SPI and a secured actuator endpoint |
| [`export-spring-boot-starter`](export-spring-boot-starter/README.md) ([ru](export-spring-boot-starter/README.ru.md)) | The platform's engine for tabular business reports: report definitions declared as typed compile-time code and validated against the whole estate at startup, a windowed streaming pipeline that walks a keyset-paginated source and never materialises a result set, cross-service enrichment through named REST clients with absence and failure as separate first-class outcomes, XLSX (streaming Apache POI, one cached style per format) and CSV behind an open format SPI, run persistence and lease-based lifecycle on `job-core`, admin-authored saved configurations and cron subscriptions, output sinks with retention, and an audit trail on every transition because a report is the estate's widest bulk read |
| [`object-storage-spring-boot-starter`](object-storage-spring-boot-starter/README.md) ([ru](object-storage-spring-boot-starter/README.ru.md)) | The platform's one bucket client, so that no module grows a second: an `ObjectStore` contract whose load-bearing method is a **ranged** read - without which resuming a 1 GB ingest at byte 800 M means re-downloading 800 MB and the whole checkpoint design collapses into "start over" - plus listing that pages lazily on S3's continuation token rather than materialising a year of daily drops, one place where `s3://bucket/key` and `file:///path` are parsed, an AWS SDK v2 implementation and a filesystem one that honours ranges natively so the resume tests prove resumption instead of asserting it, both held to a single contract test run twice; no internal retries (the SDK's own policy is configured down, because two backoff budgets multiply), and credentials only ever from the SDK's provider chain |
| [`file-ingest-spring-boot-starter`](file-ingest-spring-boot-starter/README.md) ([ru](file-ingest-spring-boot-starter/README.ru.md)) | The once-a-day bulk file ingest: reads a large object out of storage, parses it record by record, writes the records into the database and tells somebody it's done, without ever holding the file in memory and without ever losing a record. Arrival detection before the first byte is read - because a half-written object produces a *successful* run with a truncated tail that nothing alerts on; a checkpoint committed in the **same transaction** as the data it accounts for, because no ordering of two transactions avoids both duplicates and a silent gap; ranged resumption so a 1GB drop restarts where it stopped; batches bounded by count **and** bytes, so one row with a 200MB column is a quarantined poison record rather than an OutOfMemoryError; a balance check that gates COMPLETED and leaves the target untouched when it fails; exactly-once keyed on (bucket, key, etag) by a unique constraint rather than a flag, so a corrected re-upload is not silently skipped; lease renewal inside the batch loop; receipts, archival, an audit SPI, an actuator endpoint, and `ludwig.ingest.missing` for the file that never came |
| [`security-spring-boot-starter`](security-spring-boot-starter/README.md) ([ru](security-spring-boot-starter/README.ru.md)) | Enterprise-ready authentication and authorization for Spring Boot microservices behind an nginx/Envoy edge: one principal for browser users (session cookie exchanged for a JWT at the edge), mTLS partners (Envoy `x-forwarded-client-cert`) and peer services; roles resolved per service instead of from token claims; data-level authorization compiled into type-safe QueryDSL predicates plus a single-object `PermissionEvaluator`; localized RFC 7807 401/403, audit trail and metrics |
| [`identity-projection-spring-boot-starter`](identity-projection-spring-boot-starter/README.md) ([ru](identity-projection-spring-boot-starter/README.ru.md)) | Local projection of the OIDC provider's Kafka user stream and the database-backed `AuthorityResolver`/`DataScopeProvider`/`PartnerIdentityResolver` that read from it: idempotent, order-tolerant consumption, users/roles/partner registry/time-boxed data grants in Postgres, authority-cache eviction on change, and opt-in projection of the provider's verified contact data for the one service that writes to people |
| [`web-core-spring-boot-starter`](web-core-spring-boot-starter/README.md) ([ru](web-core-spring-boot-starter/README.ru.md)) | The REST foundation every service sits on: one localized RFC 9457 `ProblemDetail` pipeline that modules contribute exception mappers and message bundles to instead of each shipping its own `@RestControllerAdvice`, transport-neutral business exceptions, `Accept-Language` locale resolution wired into Bean Validation, and a paged response envelope that doesn't leak Spring Data's JSON shape |
| [`hot-reload-spring-boot-starter`](hot-reload-spring-boot-starter/README.md) ([ru](hot-reload-spring-boot-starter/README.ru.md)) | Enterprise-ready hot reload for Java 17 + Spring Boot: typed/validated configuration, live-reloading property/YAML files and FreeMarker templates, HashiCorp Vault secrets (KV polling, lease-renewed dynamic secrets, Kubernetes auth), environment variables always able to override a reloaded value |
| [`observability-spring-boot-starter`](observability-spring-boot-starter/README.md) ([ru](observability-spring-boot-starter/README.ru.md)) | The observability foundation every service sits on: distributed tracing over OTLP with a per-request sampling override, one correlation id propagated across HTTP and Kafka and into the MDC, structured JSON logging (ECS/OTel/flat) carrying trace, span and correlation ids with secret masking and truncation, RED metrics with bounded URI cardinality and aggregatable SLO histograms, split liveness/readiness probes and graceful shutdown, and log levels changeable at runtime through the hot-reload module |
| [`rest-client-spring-boot-starter`](rest-client-spring-boot-starter/README.md) ([ru](rest-client-spring-boot-starter/README.ru.md)) | Outbound HTTP as configuration: N independent named clients declared in application.yaml, each with its own connection pool, timeouts, authentication, Resilience4j policy, serialization, logging, metrics and audit, and none of them able to starve another; consumed as declarative `@LudwigRestClient` interfaces, as injectable `RestClient`/`WebClient` beans or through a registry, with one execution pipeline behind all three and identical behaviour in sync and async mode; a documented decorator order (rate limiter, bulkhead, breaker, retry, time limiter) with a retry policy that honours `Retry-After`, keeps a wall-clock budget and refuses to repeat a non-idempotent method unless the caller opts in; authentication, listeners, error translation, fallbacks and audit sinks as open SPIs; and a startup validator that rejects a configuration which is valid field by field and wrong as a whole |
| [`user-settings-spring-boot-starter`](user-settings-spring-boot-starter/README.md) ([ru](user-settings-spring-boot-starter/README.ru.md)) | The platform's engine for per-user settings, preferences and consents - the data an OIDC provider does not own: settings declared as typed compile-time constants by the consuming service, resolved through a layered user/role/tenant/platform/default chain that reports which layer answered, in one query per subject; a Caffeine cache evicted after commit rather than on a TTL; an append-only consent ledger recording the version of the text that was agreed to; PII redaction across audit, logs, metrics and problem documents; and an owner/projection split so a reading service keeps a local replica instead of calling the owner on its hot path |
| [`jira-client`](jira-client/README.md) ([ru](jira-client/README.ru.md)) | A type-safe, framework-free client for the Jira Server 9.12 REST API v2 and the ALM Works Structure 2.0 API: a JQL builder whose types decide which operators exist and whose every value is escaped, typed request builders that keep Jira's replacing `fields` and mutating `update` sections apart, a custom field registry that resolves instance-specific ids from display names once at startup, annotation-driven binding of issues onto application types, lazy streaming over offset pagination, jittered retries that never repeat a non-idempotent write, and a raw request escape hatch that reaches any unmodelled endpoint with the same auth, retries and metrics |
| [`architecture-rules`](architecture-rules/README.md) ([ru](architecture-rules/README.ru.md)) | Executable architecture conventions: a test-scoped jar of toggleable ArchUnit rule sets (layering, package cycles, REST boundary, JPA persistence and entity inheritance, Kafka messaging and contracts, S3 storage, Spring wiring, exception architecture, API model immutability, REST path versioning, configuration validation, module boundaries, test separation) that a service enables with one annotation - with per-module overrides, warn-instead-of-fail severities, an extension SPI, and a console plus JSON report per run for org-wide drift tracking |
| [`checkstyle-rules`](checkstyle-rules/README.md) ([ru](checkstyle-rules/README.ru.md)) | The shared code style, executable: a Checkstyle configuration whose formatting rules mirror IntelliJ IDEA's defaults one for one (so Reformat Code always produces a build-clean file), plus naming, source-level conventions, i18n bundle consistency and Javadoc correctness - 112 rules, wired into every module at the `validate` phase, with a separate warning tier for missing documentation and three in-code suppression forms that each have to name the rule |
| [`notification-service`](notification-service/README.md) ([ru](notification-service/README.ru.md)) | The platform's notification service, built on the modules above: Kafka and REST ingress converging on one application service, a Postgres delivery queue with `FOR UPDATE SKIP LOCKED` claiming, per-recipient-per-channel fan-out so partial failure is representable, hot-reloadable FreeMarker templates rendered strictly and versioned by content hash, channels as an SPI (SMTP, internal chat, HMAC-signed webhook) with per-provider retry classification, opt-out/quiet-hours/suppression, priority lanes and cluster-wide rate limits, delivery receipts, digesting, and the two capabilities the platform does not have yet - consumer-side idempotency and a leased distributed lock |
| [`crud-service-example`](crud-service-example/README.md) ([ru](crud-service-example/README.ru.md)) | Reference CRUD microservice built on the modules above: three model layers (DTO/domain/entity) wired by MapStruct, Lombok instead of boilerplate, compile-time-checked QueryDSL-JPA queries only, OData search, i18n validation and RFC 7807 errors, transactional outbox events |

[//]: # (| `kafka-tools`  | Kafka-related producers, consumers, and helpers |)

[//]: # (| `json-support` | JSON &#40;de&#41;serialization helpers using Jackson    |)

[//]: # (| `grpc-client`  | gRPC client utilities and wrappers              |)
[//]: # (| *...add more*  |                                                 |)

## 🚀 Getting Started

### Prerequisites

- Java 17+
- Maven 3.6+

### Cloning the Project

```bash
git clone https://github.com/LudwigAndreas/java-ludwig-common.git
cd java-ludwig-common 
```

### Building the Library

```bash
mvn clean install
```

This will compile and install all modules into your local Maven repository.

### Using a Module in Your Project

See **[Consuming the platform](#consuming-the-platform)** below - a service should not name module
versions at all.

## Consuming the platform

The build publishes three artifacts that a consumer cares about:

| Artifact | Packaging | What it gives you |
|---|---|---|
| `ludwig-bom` | `pom` | Every version. Each `ru.ludwigandreas` module, plus every third-party dependency the platform pins on top of Spring Boot (QueryDSL, MapStruct, Olingo, Resilience4j, Testcontainers, Vault, FreeMarker, ArchUnit, springdoc, ...). Nothing else - no plugins, no build configuration. |
| `ludwig-service-parent` | `pom` | Every build decision. Compiler + annotation processors in the right order, surefire/failsafe, the coverage gate, the enforcer gate, Checkstyle, and a fully configured jib. Imports `ludwig-bom`, so this one `<parent>` covers versions too. |
| `common` | `pom` | The reactor root. Parent of the **library** modules only; a service never inherits it. |

### The usual case: a microservice

One `<parent>`, and no version anywhere else:

```xml
<parent>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>ludwig-service-parent</artifactId>
    <version>1.1.0</version>
    <relativePath/>
</parent>

<groupId>com.example</groupId>
<artifactId>my-service</artifactId>
<version>0.1.0</version>

<dependencies>
    <dependency>
        <groupId>ru.ludwigandreas</groupId>
        <artifactId>db-core</artifactId>
    </dependency>
    <dependency>
        <groupId>ru.ludwigandreas</groupId>
        <artifactId>web-core-spring-boot-starter</artifactId>
    </dependency>
</dependencies>
```

`<relativePath/>` must be **present and empty**. Omit it and Maven looks for `../pom.xml`, which in a
standalone service does not exist (or, worse, is an unrelated POM), and the build fails with a
confusing *non-resolvable parent*.

Checkstyle is the one thing a service opts into, because activating it in the parent would make that
POM resolve `checkstyle-rules` for sources it does not have:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-checkstyle-plugin</artifactId>
        </plugin>
    </plugins>
</build>
```

### The other case: a library, or a service that cannot change its parent

Import the BOM instead. `ludwig-bom` is published with no parent of its own, so this works without
access to any `ru.ludwigandreas` POM other than the BOM:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>ru.ludwigandreas</groupId>
            <artifactId>ludwig-bom</artifactId>
            <version>1.1.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

You get all the versions and none of the build configuration. This is exactly what the library
modules in this repository do - see the next section for why.

### Two artifacts, one release train

The BOM and the parent are separate artifacts on purpose. They pull in opposite directions:
*"all versions in one place"* wants a single BOM everyone tracks, while *"one parent for every
service"* means a change as small as a jib label forces a parent bump that every service eventually
has to adopt. Keeping them separate lets a service take a **new BOM without new build config**, or
the reverse:

```xml
<!-- Inherit build config from 1.1.0, but take library versions from 1.3.0. -->
<parent>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>ludwig-service-parent</artifactId>
    <version>1.1.0</version>
    <relativePath/>
</parent>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>ru.ludwigandreas</groupId>
            <artifactId>ludwig-bom</artifactId>
            <version>1.3.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

A nearer import wins, so the explicit one overrides the one the parent brought in.

Be honest about the limit, though: both artifacts are built in **this** reactor from one
`${revision}`, so `ludwig-bom:1.2.0` and `ludwig-service-parent:1.2.0` are always released together.
The independence above is real on the *consuming* side only. Genuinely independent cadence would
mean moving the parent into its own repository with its own version line.

### Overriding a managed version

Redeclare the property **and** the dependency. The property alone is not enough: an imported BOM's
entries are already interpolated by the time your POM is read, so a property in a consumer cannot
reach back into them.

```xml
<properties>
    <mapstruct.version>1.6.4</mapstruct.version>
</properties>

<dependencies>
    <dependency>
        <groupId>org.mapstruct</groupId>
        <artifactId>mapstruct</artifactId>
        <version>${mapstruct.version}</version>
    </dependency>
</dependencies>
```

A *plugin* version, a coverage threshold or an enforcer rule works the simpler way - those come from
the parent by inheritance, where a child property does win on its own:

```xml
<properties>
    <jib-maven-plugin.version>3.5.0</jib-maven-plugin.version>
    <jacoco.instruction.coverage.minimum>0.80</jacoco.instruction.coverage.minimum>
    <enforcer.java.version>[21,)</enforcer.java.version>
</properties>
```

Switching a non-essential plugin off needs no fork either - each honours its standard skip property:
`-Dcheckstyle.skip`, `-Djacoco.skip`, `-Denforcer.skip`, `-Dmaven.test.skip`.

### Overriding the base image

Every image reference in this repository is pinned by **name, version and digest**. A bare tag is
not acceptable: `21-jre` gets republished, so a tag-only reference silently changes what ships
between two builds of the same commit.

```xml
<properties>
    <!-- Keep the tag next to the digest: a digest alone tells a reader nothing. -->
    <ludwig.image.base>eclipse-temurin:21.0.12_8-jre@sha256:6cbdfc89c9657478bc5abea638030310f6c0267404e98a5808097bb1925932f1</ludwig.image.base>
</properties>
```

The target image is derived, never hardcoded - a service names no repository path:

```xml
<properties>
    <ludwig.image.registry>registry.example.internal</ludwig.image.registry>
    <ludwig.image.namespace>ludwig</ludwig.image.namespace>
    <!-- => ${ludwig.image.registry}/${ludwig.image.namespace}/${project.artifactId}:${project.version} -->
</properties>
```

Other image knobs, all overridable: `ludwig.image.user` (default `1000:1000`, non-root),
`ludwig.image.jvm.flags` (heap as a percentage of the cgroup limit, never a fixed `-Xmx`),
`ludwig.image.source` and `ludwig.image.tag`.

### Building images: local vs Jenkins

**jib never runs during `mvn clean install`.** It is configured in `ludwig-service-parent` but bound
to no lifecycle phase in the default build, so a local build needs no Docker daemon and never
contacts a registry.

| Goal | Command | Needs a daemon? |
|---|---|---|
| Local image, into your own Docker | `mvn package jib:dockerBuild` | yes |
| Push to the registry | `mvn -Pci deploy` | **no** |
| Push, without the full deploy | `mvn -Pci jib:build` | **no** |

`jib:build` streams layers straight to the registry over HTTPS, which is why a CI agent needs no
Docker daemon, no privileged container and no mounted daemon socket.

Use `mvn package jib:dockerBuild`, not a bare `jib:dockerBuild`: the bare goal skips the lifecycle,
so `git-commit-id-maven-plugin` never runs and the image ships without its
`org.opencontainers.image.revision` label. Outside a git checkout entirely, pass the value in with
`-Dgit.commit.id=<sha>`.

`-Pci` is never activated automatically - not from `env.JENKINS_URL`, not from anything else. A
build that behaves differently depending on an environment variable nobody remembers setting cannot
be reproduced on a laptop, which is precisely when you need to.

### Credentials

**No credential appears in any POM**, and none should be added. jib resolves them, in order, from:

1. a `<server>` entry in `~/.m2/settings.xml` whose `<id>` equals the registry host
   (`${ludwig.image.registry}`) - on Jenkins this comes from the Maven Config File Provider,
   populated from a Jenkins credential;
2. the `JIB_TARGET_USERNAME` / `JIB_TARGET_PASSWORD` environment variables - what the `Jenkinsfile`
   binds with `withCredentials`;
3. the local Docker credential helpers, for a developer already logged in.

Maven repository credentials work the same way: `<server>` entries matching
`${ludwig.repo.releases.id}` / `${ludwig.repo.snapshots.id}`. The repository URLs are properties too,
so the same POM deploys to Nexus, Artifactory or GitHub Packages:

```bash
mvn -Pci -Dludwig.repo.snapshots.url=https://maven.pkg.github.com/acme/repo deploy
```

### Versions and releasing

The repository version is declared **once**, in `.mvn/maven.config`:

```
-Drevision=1.1.0-SNAPSHOT
```

Every POM says `<version>${revision}</version>`; `flatten-maven-plugin` rewrites it to a literal in
every POM that is installed or deployed, so nothing unresolvable is ever published. A release passes
a concrete value on the command line:

```bash
mvn -Drevision=1.2.0 -Prelease -Pci deploy
```

`-Prelease` does not *set* the version - it cannot, because `${revision}` arrives as a user property
and user properties beat any profile's `<properties>`. Instead it enforces the invariant:
`requireReleaseVersion` fails the build if you used `-Prelease` without `-Drevision`, and
`requireReleaseDeps` fails it if anything being released depends on a SNAPSHOT.

## Code style

Style is enforced by [`checkstyle-rules`](checkstyle-rules/README.md) on every module, at the
`validate` phase - before the compiler, so the feedback arrives in seconds:

```bash
mvn validate                          # style only
mvn verify                            # style first, then compile and test
mvn verify -Dcheckstyle.skip=true     # local escape hatch, never in a pipeline
```

There is no IDE code style to import. The formatting rules are IntelliJ IDEA's own defaults, so
`Ctrl+Alt+L` (Reformat Code) and `Ctrl+Alt+O` (Optimize Imports) always produce a file the build
accepts, and the `.editorconfig` at the repository root carries the few settings that need to travel
with the project.

Three tools split the work and do not overlap: `architecture-rules` owns structure and dependencies,
`checkstyle-rules` owns source text, SonarQube owns bugs and security. Each module's README states
what it deliberately leaves to the other two.

## Testing

Each module contains its own test suite. To run all tests:

```bash
mvn test
```

## Documentation

Each module contains JavaDoc comments. You can generate documentation with:

```bash
mvn javadoc:javadoc
```

For module-specific docs, navigate to the module directory and run the same command.

## Contributing

This library is currently maintained as a personal toolkit. If you want to contribute or suggest improvements:

- Fork the repo
- Create a feature branch (feature/xyz)
- Open a pull request

> Guidelines: follow clean code practices and keep modules focused.

## License

This project is licensed under the MIT License – see the LICENSE file for details.

## Author

Ludwig Andreas

[GitHub]() • [LinkedIn]()