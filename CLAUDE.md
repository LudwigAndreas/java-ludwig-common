# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repository is

`ludwig-common` is a multi-module Maven reactor (Java 17, Spring Boot 3.3.5, group `ru.ludwigandreas`)
that publishes an internal platform: a BOM, a service parent POM, a set of Spring Boot starters and
plain libraries, and two runnable services that consume them. Each module has a detailed
`README.md` (+ `README.ru.md`) — read the module README before changing that module; they state each
module's design decisions and what it deliberately leaves to other modules.

## Commands

```bash
mvn clean install                     # full reactor build: style, compile, test, install locally
mvn validate                          # Checkstyle only (bound to `validate`, runs before the compiler)
mvn -pl db-core -am test              # build one module and its reactor dependencies
mvn -pl notification-service test -Dtest=ChannelDispatcherTest          # one test class
mvn -pl notification-service test -Dtest=ChannelDispatcherTest#rendersTemplate  # one method
mvn -pl crud-service-example verify   # unit (surefire) + integration (failsafe) + coverage gate
mvn -pl crud-service-example spring-boot:run -Dspring-boot.run.profiles=local
mvn javadoc:javadoc
```

Escape hatches for a local loop only, never in a pipeline: `-Dcheckstyle.skip=true`, `-Djacoco.skip`,
`-Denforcer.skip`, `-DskipTests`.

Images: jib is configured but bound to no phase in the default build, so `mvn clean install` never
needs Docker or a registry. Use `mvn package jib:dockerBuild` for a local image (`package` first, so
`git-commit-id-maven-plugin` populates the revision label) and `mvn -Pci jib:build` / `mvn -Pci deploy`
to push. `-Pci` is never auto-activated from the environment — always pass it explicitly.

## The three-POM split (the thing to get right)

| POM | Role |
|---|---|
| `pom.xml` (root, artifact `common`) | Reactor + **parent of the library modules only**. Imports `spring-boot-dependencies` so libraries don't bake in a Boot line. Never imports `ludwig-bom` (it is the BOM's parent — circular). |
| `ludwig-bom` | Versions and nothing else. Published parentless. Every module in the repo imports it (`type=pom`, `scope=import`); consumers do the same. |
| `ludwig-service-parent` | All build decisions services inherit: compiler + annotation processors in Lombok→MapStruct→QueryDSL order, surefire/failsafe split, enforced JaCoCo gate (70% instruction / 60% branch), enforcer, Checkstyle, jib. Its own parent is `spring-boot-starter-parent`. |

Consequences when editing POMs:
- A **library** module is parented by the reactor root and imports `ludwig-bom`. A **service**
  (`crud-service-example`, `notification-service`) is parented by `ludwig-service-parent`.
- Third-party versions belong in `ludwig-bom/pom.xml`, not the root POM. Plugin versions and build
  configuration belong in the root POM (libraries) or `ludwig-service-parent` (services).
- The version lives in exactly one place: `-Drevision=…` in `.mvn/maven.config`. Every POM says
  `<version>${revision}</version>`; `flatten-maven-plugin` resolves it on install/deploy. A release is
  `mvn -Drevision=1.2.0 -Prelease -Pci deploy` — `-Prelease` only enforces (no SNAPSHOT deps,
  concrete revision), it cannot set the version.
- `spring-boot.version` appears twice on purpose: as a property in the root POM and as a literal in
  `ludwig-service-parent`'s `<parent>` (Maven does not interpolate there). Move both together.
- JUnit is deliberately unpinned anywhere — it comes from `spring-boot-dependencies`.

## Module conventions

- Package root per module is `ru.ludwigandreas.<module>`; starters register auto-configuration in
  `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
- Starters ship their own i18n bundle under `src/main/resources/i18n/ludwig-<module>-messages[_ru].properties`
  and contribute exception mappers into `web-core`'s single RFC 9457 `ProblemDetail` pipeline rather
  than shipping their own `@RestControllerAdvice`. User-facing text is never hard-coded (Checkstyle's
  `NonAsciiSourceText` enforces this); bundle key sets must match across locales.
- Services follow the reference shape in `crud-service-example`: three model layers
  `web.dto` → `service.model` → `repository.entity`, wired by MapStruct; Lombok instead of
  boilerplate; **QueryDSL-JPA against generated Q-types only** — no JPQL/SQL strings, no derived
  query methods; transactions in the service layer; Liquibase changelogs under
  `src/main/resources/db/changelog`.
- Every container image reference is pinned by name, version **and** `sha256` digest. A bare tag is
  not acceptable anywhere, including in READMEs and docker run examples.
- **One carve-out from the QueryDSL-only rule exists, and it has a boundary.**
  `ru.ludwigandreas.ingest.bulk` in `file-ingest-spring-boot-starter` may contain SQL strings, because
  two statements cannot be written in QueryDSL and both are load-bearing: Postgres `COPY` (via
  `CopyManager`, which streams and is several times faster than batched `INSERT`) and the set-based
  `INSERT … SELECT … ON CONFLICT DO UPDATE` that merges a staging table into a target. The
  alternative is reading four million staged rows into the JVM to write them back one at a time,
  which is the behaviour that module exists to avoid. The conditions: every SQL string stays in that
  one package; each statement carries javadoc saying why QueryDSL cannot express it (the standard
  `DistributedLockRepository` and `IdempotencyRecordRepository` already meet for their native
  `@Query`s); `SqlConfinementTest` in that module fails the build if SQL or JDBC appears anywhere
  else in it. Nothing outside that package, and no other module, has this exception - the ingest
  module's own tables are queried through QueryDSL predicates like everything else.

## Tests

- Layout is `src/test/java/ru/ludwigandreas/<module>/{unit,integration,architecture}`.
- Surefire runs unit tests and **excludes** `*IT.java` and `*IntegrationTest.java`; failsafe runs those
  at `verify`. So an integration test must be named `…IT` or `…IntegrationTest` to run at all, and
  `mvn test` alone will not run it.
- `architecture-rules` is a test-scoped jar of toggleable ArchUnit rule sets. A service enables it with
  a subclass of `ArchitectureRulesTest` annotated `@AnalyzeArchitecture(...)`, plus
  `src/test/resources/architecture-rules.properties` for the service-specific base types. Each run
  writes `target/architecture-report.json`.

## Style

Checkstyle (`checkstyle-rules`) runs at `validate` on every module; in-reactor builds read the config
straight from that module's source tree, so a clean clone builds without installing it first.

The formatting rules mirror **IntelliJ IDEA's out-of-the-box defaults** one for one — there is no IDE
code style to import, and Reformat Code / Optimize Imports always produce a build-clean file. Do not
introduce a custom formatting scheme.

Three tools split the work and must not overlap: `architecture-rules` owns structure and dependencies,
`checkstyle-rules` owns source text, SonarQube owns bugs and security.

Suppressions must always name the rule and give a reason:

```java
@SuppressWarnings("checkstyle:MagicNumber")                 // whole declaration
// CHECKSTYLE.OFF: IllegalCatch - reason        ... // CHECKSTYLE.ON: IllegalCatch
// SUPPRESS CHECKSTYLE VisibilityModifier - reason          // next line
```

## Credentials

No credential appears in any POM. jib and `deploy` resolve them from `~/.m2/settings.xml` `<server>`
entries matching the registry host / `ludwig.repo.{releases,snapshots}.id`, from
`JIB_TARGET_USERNAME`/`JIB_TARGET_PASSWORD`, or from local Docker credential helpers.
