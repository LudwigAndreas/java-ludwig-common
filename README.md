# ludwig-common

> The shared Java 17 / Spring Boot platform: libraries, starters, build artifacts and services

## 📦 Overview

The shared platform for Java 17 / Spring Boot microservices: libraries and starters for persistence, messaging,
security, observability, files and reports; two published build artifacts (`ludwig-bom`,
`ludwig-service-parent`); and two services built on them. One Maven reactor, one version.

**Documentation by audience** is indexed in [docs/README.md](docs/README.md): the
[developer guide](docs/developer-guide.md), the [administrator guide](docs/admin-guide.md) and the
[analyst guide](docs/analyst-guide.md).

## 📁 Project Structure

```text
ludwig-common/
│
├── .mvn/maven.config        # -Drevision=… — the tag-less FALLBACK. CI computes the real one
├── gitversion.yml           # …from the git history. A release is a tag, not an edit
├── CHANGELOG.md             # One changelog for the reactor; CHANGELOG.ru.md is its pair
├── pom.xml                  # Reactor root: modules, build config, scm, distributionManagement.
│                            #   Parent of the LIBRARY modules only.
├── Jenkinsfile              # How the platform ships: build, verify, deploy, push images
│
├── build/ludwig-bom/              # Published: every version, and nothing else
│ └── pom.xml
├── build/ludwig-service-parent/   # Published: every build decision. What a microservice inherits
│ └── pom.xml
│
├── sources/audit-core/              # Library modules — parented by the reactor root,
│ └── pom.xml                #   each importing ludwig-bom for versions
├── sources/db-core/
│ └── pom.xml
├── sources/job-core/
│ └── pom.xml
├── <other starters>/
│ └── pom.xml
│
├── services/crud-service-example/    # Services — parented by ludwig-service-parent,
│ └── pom.xml                #   shipped as container images
├── services/notification-service/
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

Every module's README follows one template: usage, configuration, deployment, observability, audit, runbook, testing and design decisions (see [docs/documentation-guide.md](docs/documentation-guide.md)). `PROJECT_INDEX.md` is the generated dependency map.

### Build and versioning

| Module | What it is |
|---|---|
| [`ludwig-bom`](build/ludwig-bom/README.md) ([ru](build/ludwig-bom/README.ru.md)) | Version registry: every platform module and every third-party pin. Import it and name no versions |
| [`ludwig-service-parent`](build/ludwig-service-parent/README.md) ([ru](build/ludwig-service-parent/README.ru.md)) | Parent POM of every service: compiler and annotation processors, surefire/failsafe, coverage gate, enforcer, Checkstyle, Jib |
| [`ludwig-grpc-api-parent`](build/ludwig-grpc-api-parent/README.md) ([ru](build/ludwig-grpc-api-parent/README.ru.md)) | Parent POM of every `<service>-grpc-api` contract module: gRPC/protobuf dependencies, `grpc-core`, code generation |
| [`architecture-rules`](build/architecture-rules/README.md) ([ru](build/architecture-rules/README.ru.md)) | Executable architecture conventions: toggleable ArchUnit rule sets with console and JSON reports |
| [`checkstyle-rules`](build/checkstyle-rules/README.md) ([ru](build/checkstyle-rules/README.ru.md)) | The shared Checkstyle configuration, mirroring IntelliJ IDEA's formatter; runs at `validate` in every module |
| [`jacoco-aggregate`](build/jacoco-aggregate/README.md) ([ru](build/jacoco-aggregate/README.ru.md)) | One coverage report across the reactor; the only coverage input of the SonarCloud analysis |

### Persistence, jobs and audit

| Module | What it is |
|---|---|
| [`db-core`](sources/db-core/README.md) ([ru](sources/db-core/README.ru.md)) | Base entities, JPA auditing columns, soft delete, snapshot immutability, QueryDSL repository base, persistence exceptions |
| [`job-core`](sources/job-core/README.md) ([ru](sources/job-core/README.ru.md)) | Worker mechanics: backoff with jitter, `SelfSchedulingLifecycle`, `SKIP LOCKED` claims, instance identity, the leased `RunLock` |
| [`audit-core`](sources/audit-core/README.md) ([ru](sources/audit-core/README.ru.md)) | One `AuditEvent` envelope, the `AuditSink` with a configurable failure policy, redaction, `ActorResolver`; no in-repo dependencies |
| [`audit-spring-boot-starter`](sources/audit-spring-boot-starter/README.md) ([ru](sources/audit-spring-boot-starter/README.ru.md)) | Durable audit trail: append-only `audit_event`, JPA and outbox sinks, per-category retention, a query side |
| [`idempotency-spring-boot-starter`](sources/idempotency-spring-boot-starter/README.md) ([ru](sources/idempotency-spring-boot-starter/README.ru.md)) | Work dedup: atomic key claims in `TRANSACTIONAL` or `STANDALONE` mode, HTTP response replay, Kafka record filter, retention purge |
| [`cache-spring-boot-starter`](sources/cache-spring-boot-starter/README.md) ([ru](sources/cache-spring-boot-starter/README.ru.md)) | Named, typed caches with a declared purpose (security or performance), Caffeine local tier, optional Redis shared tier, after-commit eviction |

### Messaging and integration

| Module | What it is |
|---|---|
| [`outbox-spring-boot-starter`](sources/outbox-spring-boot-starter/README.md) ([ru](sources/outbox-spring-boot-starter/README.ru.md)) | Transactional outbox: `SKIP LOCKED` relay, retry and dead-letter, ordering keys, Kafka/REST/DB routing, declared event types |
| [`messaging-spring-boot-starter`](sources/messaging-spring-boot-starter/README.md) ([ru](sources/messaging-spring-boot-starter/README.ru.md)) | The consumer side of the outbox envelope: one container factory, backoff then DLT, typed inbound envelope, lag and silence metrics, multi-cluster Kafka |
| [`messaging-db-spring-boot-starter`](sources/messaging-db-spring-boot-starter/README.md) ([ru](sources/messaging-db-spring-boot-starter/README.ru.md)) | A removable stand-in for a Kafka cluster: a Kafka-shaped log in the shared PostgreSQL; services keep using the outbox and `@KafkaListener` unchanged |
| [`asyncapi-spring-boot-starter`](sources/asyncapi-spring-boot-starter/README.md) ([ru](sources/asyncapi-spring-boot-starter/README.ru.md)) | Generates the service's AsyncAPI document from declared event types and listeners |
| [`reconciliation-spring-boot-starter`](sources/reconciliation-spring-boot-starter/README.md) ([ru](sources/reconciliation-spring-boot-starter/README.ru.md)) | Keeps local mirrors of externally owned state in sync: four fetch shapes, staging, quotas, leader election, freshness metrics |
| [`rest-client-spring-boot-starter`](sources/rest-client-spring-boot-starter/README.md) ([ru](sources/rest-client-spring-boot-starter/README.ru.md)) | Named outbound HTTP clients from configuration: pools, timeouts, auth, Resilience4j, metrics, audit, `@LudwigRestClient` interfaces |
| [`outbound-token-core`](sources/outbound-token-core/README.md) ([ru](sources/outbound-token-core/README.ru.md)) | Outbound credentials shared by both client starters: cached client-credentials tokens and bearer relay |
| [`jira-client`](sources/jira-client/README.md) ([ru](sources/jira-client/README.ru.md)) | Framework-free client for Jira Server REST v2 and ALM Works Structure: typed JQL, field registry, streaming pagination |

### gRPC

| Module | What it is |
|---|---|
| [`grpc-core`](sources/grpc-core/README.md) ([ru](sources/grpc-core/README.ru.md)) | Shared gRPC vocabulary: metadata keys, problem-to-status mapping, the `Operation` message, context propagation |
| [`grpc-server-spring-boot-starter`](sources/grpc-server-spring-boot-starter/README.md) ([ru](sources/grpc-server-spring-boot-starter/README.ru.md)) | `@LudwigGrpcService` beans behind a fixed interceptor chain: auth, problem mapping, admission limits, TLS, health |
| [`grpc-client-spring-boot-starter`](sources/grpc-client-spring-boot-starter/README.md) ([ru](sources/grpc-client-spring-boot-starter/README.ru.md)) | Named gRPC clients from configuration: deadlines, resilience, contract-aware retry, token relay, metrics |
| [`catalog-grpc-api`](sources/catalog-grpc-api/README.md) ([ru](sources/catalog-grpc-api/README.ru.md)) | The product catalog's gRPC contract, served by `crud-service-example`; the reference contract module |

### Web, security and identity

| Module | What it is |
|---|---|
| [`web-core-spring-boot-starter`](sources/web-core-spring-boot-starter/README.md) ([ru](sources/web-core-spring-boot-starter/README.ru.md)) | One localized RFC 9457 `ProblemDetail` pipeline, business exceptions, locale resolution, page envelope, the long-running operation contract |
| [`security-spring-boot-starter`](sources/security-spring-boot-starter/README.md) ([ru](sources/security-spring-boot-starter/README.ru.md)) | Authentication (browser JWT, mTLS partners, peer services), per-service roles, data scopes compiled into QueryDSL predicates |
| [`pat-core`](sources/pat-core/README.md) ([ru](sources/pat-core/README.ru.md)) | Personal access token format: wire format, masked secret, digest, scopes, the `ludwig_pat` claim; no Spring, no storage |
| [`pat-spring-boot-starter`](sources/pat-spring-boot-starter/README.md) ([ru](sources/pat-spring-boot-starter/README.ru.md)) | The issuing half of PATs for the identity-provider service: `ludwig_pat` table, management API, token exchange, introspection (verification is in the security starter) |
| [`identity-projection-spring-boot-starter`](sources/identity-projection-spring-boot-starter/README.md) ([ru](sources/identity-projection-spring-boot-starter/README.ru.md)) | Local projection of the OIDC provider's user stream backing role, data-scope and partner resolution |
| [`odata-filter-spring-boot-starter`](sources/odata-filter-spring-boot-starter/README.md) ([ru](sources/odata-filter-spring-boot-starter/README.ru.md)) | OData `$filter`/`$top`/`$skip`/`$orderby` for Spring Data JPA REST APIs with a field allowlist |
| [`user-settings-spring-boot-starter`](sources/user-settings-spring-boot-starter/README.md) ([ru](sources/user-settings-spring-boot-starter/README.ru.md)) | Per-user settings, preferences and consents with layered resolution, consent ledger and owner/projection modes |

### Files, reports and storage

| Module | What it is |
|---|---|
| [`object-storage-spring-boot-starter`](sources/object-storage-spring-boot-starter/README.md) ([ru](sources/object-storage-spring-boot-starter/README.ru.md)) | One `ObjectStore` contract with ranged reads and lazy listing; S3 and filesystem implementations |
| [`file-ingest-spring-boot-starter`](sources/file-ingest-spring-boot-starter/README.md) ([ru](sources/file-ingest-spring-boot-starter/README.ru.md)) | Daily bulk file ingest: arrival detection, checkpointed resumable parsing, quarantine, balance check, exactly-once per object version |
| [`file-action-spring-boot-starter`](sources/file-action-spring-boot-starter/README.md) ([ru](sources/file-action-spring-boot-starter/README.ru.md)) | Interactive spreadsheet actions: upload XLSX/CSV, bounded parsing, confirm step, commit policy, reject report, retention and audit |
| [`export-spring-boot-starter`](sources/export-spring-boot-starter/README.md) ([ru](sources/export-spring-boot-starter/README.ru.md)) | Tabular business reports: typed definitions, streaming XLSX/CSV, enrichment, run lifecycle, subscriptions, audit |

### Operations

| Module | What it is |
|---|---|
| [`observability-spring-boot-starter`](sources/observability-spring-boot-starter/README.md) ([ru](sources/observability-spring-boot-starter/README.ru.md)) | Tracing over OTLP, correlation ids across HTTP and Kafka, structured JSON logs, RED metrics, probes, build identity |
| [`hot-reload-spring-boot-starter`](sources/hot-reload-spring-boot-starter/README.md) ([ru](sources/hot-reload-spring-boot-starter/README.ru.md)) | Live reload of property/YAML files, FreeMarker templates and Vault secrets into typed configuration |

### Test support

| Module | What it is |
|---|---|
| [`test-support`](sources/test-support/README.md) ([ru](sources/test-support/README.ru.md)) | Digest-pinned container images, shared Testcontainers singletons, composed test annotations, fixtures, published-document check |
| [`test-support-security`](sources/test-support-security/README.md) ([ru](sources/test-support-security/README.ru.md)) | Test principals and a reject-all `JwtDecoder` for security-aware tests |
| [`test-support-local`](sources/test-support-local/README.md) ([ru](sources/test-support-local/README.ru.md)) | Run a whole service locally against real stand-ins (OIDC, SMTP, HTTP partners) from the IDE |

### Services

| Module | What it is |
|---|---|
| [`notification-service`](services/notification-service/README.md) ([ru](services/notification-service/README.ru.md)) | The platform's notification service: REST/Kafka ingress, Postgres delivery queue, templates, channels, inbox, announcements |
| [`crud-service-example`](services/crud-service-example/README.md) ([ru](services/crud-service-example/README.ru.md)) | Reference CRUD service (product catalog) showing how the modules fit together; the copy-from example |

## 🚀 Getting Started

### Prerequisites

- Java 17+
- Maven 3.9+
- **Docker, running.** Every integration test in this reactor is Testcontainers-backed. Without a
  daemon they do not skip, they *error*, and `mvn clean install` fails on `test-support`.

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

**A release is a tag.** Push `v1.2.0` onto `master` and the pipeline does the rest:

```bash
git tag -a v1.2.0 -m 'Release 1.2.0' && git push origin v1.2.0
```

Every POM says `<version>${revision}</version>`; `flatten-maven-plugin` rewrites it to a literal in
every POM that is installed or deployed, so nothing unresolvable is ever published. What *sets*
`${revision}` is the git history: GitVersion (configured in `gitversion.yml`, run from a
digest-pinned container in the `Version` stage of the `Jenkinsfile`) computes `MajorMinorPatch`, and
the pipeline adds the suffix itself — a bare `x.y.z` on a tagged commit, `x.y.z-SNAPSHOT` on every
other build, so Maven's release/snapshot repository split keeps deciding where an artifact lands. A
GitVersion pre-release label such as `1.3.0-alpha.4` is deliberately not used: Maven would treat it
as a release.

`.mvn/maven.config` still carries a `-Drevision`, and it is now **only** the fallback for a checkout
with no tags and a laptop with no GitVersion. It must stay a `-SNAPSHOT`, an enforcer rule fails the
build if it does not, and **it is not edited to cut a release** — a value in it that disagrees with
the history is a trap for the next local build.

The command the pipeline runs, reproducible by hand because no profile is ever activated
implicitly:

```bash
mvn -Drevision=1.2.0 -Prelease -Pci deploy
```

`-Prelease` does not *set* the version - it cannot, because `${revision}` arrives as a user property
and user properties beat any profile's `<properties>`. Instead it enforces the invariants:
`requireReleaseVersion` fails the build if you used `-Prelease` without `-Drevision`,
`requireReleaseDeps` fails it if anything being released depends on a SNAPSHOT, and two
`evaluateBeanshell` rules fail it unless **both** `CHANGELOG.md` and `CHANGELOG.ru.md` carry a
`## [1.2.0]` heading - naming whichever locale is missing.

### What a published library ships

Under `-Pci`, every jar module parented by the reactor root publishes its jar, its flattened POM, a
`-sources` jar and a `-javadoc` jar. A default `mvn clean install` builds none of the last two, so
the local loop stays fast. Javadoc *correctness* is gated - a broken `{@link}` or an unknown tag
fails the build - while Javadoc *completeness* stays the existing Checkstyle warning tier. The
documentation is rendered from a delomboked copy of the sources, because four public methods return
a Lombok-`@Builder` generated type that exists only in bytecode.

The two services publish neither: they are consumed as images, not as dependencies.

### API compatibility

From `v1.1.0` onwards, `revapi` compares every published library against the newest release of the
same coordinates and the version increment decides what is allowed: a **major** permits a
binary-breaking difference, a **minor** a non-breaking one, a **patch** only an equivalent API. The
gate refuses an increment that is too small and can never refuse one that is too large - only the
changelog can catch a team that bumps the major every release.

`@Deprecated` must declare `since` and `forRemoval` and carry a Javadoc `@deprecated` tag naming the
replacement; Checkstyle enforces that half at `validate`. An element is not removed unless the
published baseline already carried `forRemoval = true`, and a removal needs an entry in
`revapi.differences` with a justification naming the release that deprecated it.

## Code style

Style is enforced by [`checkstyle-rules`](build/checkstyle-rules/README.md) on every module, at the
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

### Static analysis (SonarCloud)

The third tool runs on SonarCloud and is an explicit command, never a side effect of the build:
`mvn clean install` does not contact SonarCloud.

```bash
mvn clean install                                                     # classes, tests, coverage report
SONAR_TOKEN=... mvn org.sonarsource.scanner.maven:sonar-maven-plugin:sonar
```

The full coordinates are deliberate: the version then comes from the root POM's `pluginManagement`.
Everything the scanner needs is a property of the root POM - `sonar.host.url`,
`sonar.organization`, `sonar.projectKey` - so `-Dsonar.projectKey=...` points an analysis elsewhere
without an edit. There is no `sonar-project.properties`; the Maven scanner does not read one.

Coverage is read from one file, the aggregate JaCoCo report written by `build/jacoco-aggregate`
(`sonar.coverage.jacoco.xmlReportPaths`). That is why the analysis follows a **full** build: a
narrow `-pl` build does not produce the report, and the analysis would show 0% coverage.
`scripts/check_aggregate_report.py` fails if the property and the report's location ever disagree.

The token is the `SONAR_TOKEN` environment variable and appears in no POM. In the pipeline it is
the Jenkins secret-text credential `sonarcloud-token`, bound for the `Sonar analysis` stage only.
The quality gate is reported in SonarCloud and does not fail the build.

## Testing

Each module contains its own test suite. To run all tests:

```bash
mvn test
```

## Documentation

- [docs/README.md](docs/README.md): index of all documentation by audience.
- Each module's `README.md` / `README.ru.md`: how to use, configure, deploy, observe and operate it.
- [docs/api/](docs/api/README.md): the published OpenAPI and AsyncAPI documents.
- Javadoc: `mvn javadoc:javadoc`.

## Contributing

**Start with [`docs/runbook.md`](docs/runbook.md).** It is the development and release process end to
end: how to add a feature, how to write a fix, how versions are computed, where IFT sits, and how a
minor, major and hotfix release each happen. A new developer should not need anything else to make
their first change.

The short version:

- Branch from `develop` as `feature/…` or `fix/…`.
- **Write the OpenSpec change before the code** — `openspec new change <name>`. No code without one.
- Run `scripts/gate.sh --change <name> <module>`; it works out the in-repo dependents for you.
- Open the PR against `develop`. (IFT deployment is not wired into the Jenkinsfile yet; see the runbook.)
- A release is a **tag**, never an edit to a version file.

> Guidelines: follow clean code practices and keep modules focused. What the code must look like is
> in [`CLAUDE.md`](CLAUDE.md) and each module's own README.

## License

This project is licensed under the MIT License – see the LICENSE file for details.

## Author

Ludwig Andreas

[GitHub]() • [LinkedIn]()