# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Start here (agent operating protocol)

Do these in order. They are cheap and nothing else works without them.

1. **Bootstrap the code index.** Call the `code-index` MCP server: `set_project_path` on the repo
   root, then `build_deep_index`. ~15s, 2153 files, 10,233 symbols. Then query through it —
   `find_files` → `get_file_summary` → `get_symbol_body` → `search_code_advanced`.
   `PROJECT_INDEX.md` and `scripts/manifest.sh module <path>` answer the Maven questions the index
   cannot (module role, POM tier, dependency direction both ways, the gate). `rg` is the last rung
   and requires **stating in your response why the index could not answer**.
   Read `docs/code-index.md` for the ladder and the six verified index limits — in particular
   `called_by` is intra-file only and will tell you a method has no callers when it has 67.

2. **Work through OpenSpec.** No code without a change. Artifact order is
   `proposal` → `design` (when the change adds a module, crosses a module boundary, or touches a
   shared contract) → `specs` → `tasks` → apply. Run `openspec validate <change>` before calling a
   change ready. Project conventions reach the artifacts through `openspec/config.yaml`; the
   cross-module contracts you are deltaing against live in `openspec/specs/`.

3. **The verification gate**, in full, for every change:

   ```bash
   scripts/gate.sh --change <change> <module> [<module>...]   # runs all of the below
   scripts/gate.sh --list <module>                            # or just print the commands
   ```

   which is, by hand:

   ```bash
   scripts/manifest.sh build          # only if a POM changed
   mvn -q validate                    # Checkstyle, every module
   mvn -pl <touched-module> -am verify
   mvn -pl <each in-repo dependent> -am verify   # from scripts/manifest.sh module <path>
   openspec validate <change>
   ```

   The dependents step is not optional — `-am` builds a module's *dependencies*, and dependents are
   the direction that catches a breaking change. `mvn test` alone does **not** run integration
   tests; see the test naming rule below. A change to `ludwig-bom` or `ludwig-service-parent` has
   no narrower gate than `mvn clean install`, and `gate.sh` says so rather than letting you run a
   partial one.

4. **Restrictions.** Do not edit anything under `target/`, `generated-sources/`, or
   `.flattened-pom.xml` (build output). Do not edit `.mvn/maven.config` **at all** — the version
   is computed by GitVersion and passed in as `-Drevision`, a release is a tag, and that file now
   holds only the tag-less local fallback, which must stay a `-SNAPSHOT`. Do not run `mvn deploy`, `jib:build`, or
   anything with `-Pci`, and never `git push --force`. Do not introduce a second code index
   (`universal-ctags`, `ast-grep`, `scip-java`, `semgrep`) — there is one, and it is the MCP
   server. The platform-level "one mechanism" rules — one audit sink, one operation envelope, one
   cache primitive, one ProblemDetail pipeline, one redaction mask — are enforced by ArchUnit and
   Checkstyle and will fail your build; see the module conventions below.

5. **Encode every rule twice.** A convention that exists only as prose gets broken silently. If a
   change introduces a rule, add the check that fails the build when it is violated, or write a
   comment at the point of the rule saying why no check is possible.

6. **Durable state.** A change's `openspec/changes/<name>/state.json` is the record, not the
   conversation. Write its `contract` block before the first edit and never edit it afterwards;
   append to `decisions` and `failures` and never rewrite them; read it first when resuming. Three
   attempts per task, then stop and report — never widen the scope or weaken a rule to make
   progress. Contract and schema: `docs/agent-state.md`.

Further reading: `docs/runbook.md` (the human-facing development and release process — branching,
IFT, how a feature, a fix, a hotfix and a release each actually happen),
`docs/code-index.md` (navigation and the index's limits),
`docs/agent-operations.md` (the shared agent protocol, injected for every agent),
`docs/agent-state.md` (state and receipt files), `docs/harness-enforcement.md` (which of these rules
is actually checked and which is only written down), `openspec/specs/` (the cross-module contracts a
change deltas against).

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

# Aggregate coverage across the whole platform. The report is written by the last module in the
# reactor, so it needs a full build; a narrow `-pl` build does not produce it.
mvn clean install && open build/jacoco-aggregate/target/site/jacoco-aggregate/index.html
python3 scripts/check_aggregate_report.py   # asserts every jar module is actually in the report
```

Escape hatches for a local loop only, never in a pipeline: `-Dcheckstyle.skip=true`, `-Djacoco.skip`,
`-Denforcer.skip`, `-DskipTests`.

Images: jib is configured but bound to no phase in the default build, so `mvn clean install` never
needs Docker or a registry. Use `mvn package jib:dockerBuild` for a local image (`package` first, so
`git-commit-id-maven-plugin` populates the revision label) and `mvn -Pci jib:build` / `mvn -Pci deploy`
to push. `-Pci` is never auto-activated from the environment — always pass it explicitly.

## Repository layout

Every module sits one level down, in the directory its role dictates. Nothing else is a module.

| Directory | Holds | Count |
|---|---|---|
| `build/` | what every other module inherits, imports or is checked by: `ludwig-bom`, `ludwig-service-parent`, `checkstyle-rules`, `architecture-rules` | 4 |
| `services/` | modules parented by `ludwig-service-parent`, shipped as container images | 2 |
| `sources/` | every library, starter and test-support module, parented by the reactor root | 23 |

Two consequences that bite:

- **A module POM parented by `common` must declare `<relativePath>../../pom.xml</relativePath>`.**
  Maven's default is `../pom.xml`, which from one level down is a directory with no POM — and Maven
  does not fail, it silently resolves the parent from `~/.m2` instead and builds against a stale
  `common`. Every existing module has it; a new one must too.
- **Select modules by `-pl :<artifactId>`, never by directory.** A path is a layout decision and has
  already moved once; an artifactId is a published coordinate. `scripts/gate.sh` and
  `scripts/manifest.sh module <path>` print the `:` form.

`scripts/manifest.sh layout` fails when a module is in the wrong directory for its role, and the
gate runs it. The check lives there rather than in ArchUnit or Checkstyle because a directory's
position is a filesystem fact, which bytecode and source-text analysis both cannot see. The spec is
the `repository-layout` capability in `openspec/specs/`.

## The three-POM split (the thing to get right)

| POM | Role |
|---|---|
| `pom.xml` (root, artifact `common`) | Reactor + **parent of the library modules only**. Imports `spring-boot-dependencies` so libraries don't bake in a Boot line. Never imports `ludwig-bom` (it is the BOM's parent — circular). |
| `ludwig-bom` | Versions and nothing else. Published parentless. Every module in the repo imports it (`type=pom`, `scope=import`); consumers do the same. |
| `ludwig-service-parent` | All build decisions services inherit: compiler + annotation processors in Lombok→MapStruct→QueryDSL order, surefire/failsafe split, enforced JaCoCo gate (70% instruction / 60% branch), enforcer, Checkstyle, jib. Its own parent is `spring-boot-starter-parent`. |

Consequences when editing POMs:
- A **library** module is parented by the reactor root and imports `ludwig-bom`. A **service**
  (`crud-service-example`, `notification-service`) is parented by `ludwig-service-parent`.
- Third-party versions belong in `build/ludwig-bom/pom.xml`, not the root POM. Plugin versions and build
  configuration belong in the root POM (libraries) or `ludwig-service-parent` (services).
- The version is expressed in exactly one way — `${revision}` in every POM, resolved by
  `flatten-maven-plugin` on install/deploy — and it is **computed, not written**: GitVersion reads
  the git history and CI passes `-Drevision=<computed>` on the command line. `.mvn/maven.config`
  keeps a `-Drevision` value as the fallback for a checkout with no tags, and a command-line user
  property beats it. **A release is a tag** (`v1.2.0` on `master`), not an edit to that file; an
  enforcer rule fails the build if it ever holds a non-SNAPSHOT, and the hook refuses to edit it.
  The pipeline then runs `mvn -Drevision=1.2.0 -Prelease -Pci deploy` — `-Prelease` only enforces
  (no SNAPSHOT deps, concrete revision, a changelog heading in both locales), it cannot set the
  version.
- `spring-boot.version` appears twice on purpose: as a property in the root POM and as a literal in
  `ludwig-service-parent`'s `<parent>` (Maven does not interpolate there). Move both together.
- JUnit is deliberately unpinned anywhere — it comes from `spring-boot-dependencies`.

## Module conventions

- Package root per module is `ru.ludwigandreas.<module>`; starters register auto-configuration in
  `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
- Starters ship their own i18n bundle under `src/main/resources/i18n/ludwig-<module>-messages[_ru].properties`
  and contribute exception mappers into `web-core`'s single RFC 9457 `ProblemDetail` pipeline rather
  than shipping their own `@RestControllerAdvice`.
- **Auditing goes through `audit-core`'s one `AuditSink`.** A module must not declare an audit SPI of its
  own, must not create a logger named `*.audit`, and must not declare a redaction mask - there is one, on
  `Redaction.MASK`, and it is deliberately not configurable. Keep the module's typed event record as the
  authoring surface and give it a `toAuditEvent()`. Do not put a `try`/`catch` around `record`: whether a
  sink failure fails the caller is `AuditFailurePolicy`, resolved from configuration, and a `catch` in a
  library overrides it. An ArchUnit rule (`RuleGroup.AUDIT`) and a Checkstyle rule
  (`SecondRedactionMask`) fail the build on the first two and the third respectively; the split is
  deliberate, because ArchUnit cannot see a string constant's value. User-facing text is never hard-coded (Checkstyle's
  `NonAsciiSourceText` enforces this); bundle key sets must match across locales.
- **Long-running operations go through `web-core`'s one contract.** `ru.ludwigandreas.webcore.operation`
  owns the vocabulary (`OperationStatus`: `PENDING RUNNING SUCCEEDED FAILED CANCELLED EXPIRED`), the
  `OperationResponse` envelope, the header names and the builders. A module must not declare a status
  enum that restates those six states - export's `RunStatus` and file-ingest's `IngestRunStatus` were
  exactly that, and reconciling them cost a Liquibase changeset because `COMPLETED` was persisted. A
  richer domain lifecycle is fine and is not the same thing: it maps onto the core and stays visible in
  `OperationResponse.detail()`, which is what `reconciliation`'s `RemoteJobState` does. An ArchUnit rule
  (`RuleGroup.OPERATIONS`) fails the build on the restatement; its javadoc states precisely what
  separates the two, because the difference is the whole rule. There is deliberately **no shared
  operation table** and `web-core` must stay free of any persistence dependency: each module keeps its
  own run table and maps onto the envelope at its edge. Build the responses with `OperationResponses`
  rather than by hand - it refuses a 202 with no status-resource header, a non-terminal poll with no
  `Retry-After`, and a terminal success with no result link. A 202 *may* carry a terminal envelope:
  notification fans a request out in the transaction that accepts it and still answers 202, because a
  200 there would be a different promise. Cancellation is cooperative
  (`Cancellation`), a cancel endpoint answers **202 not 204** because the stop is only requested, and
  cancelling an already-terminal operation returns the envelope rather than a 409.
- **Caching goes through `cache-spring-boot-starter`'s one primitive.** A module must not build a `Caffeine`
  builder of its own and must not declare a cache SPI - `security`'s `AuthorityCache` and `user-settings`'
  `SettingsCache` were the same three classes written twice, down to the `(Duration ttl, long maximumSize)`
  constructor and the thundering-herd paragraph, and neither called `recordStats()`, so hit ratio was
  unobservable platform-wide. A module declares a `CacheDefinition` bean (`AuthorityCaches`,
  `SettingsCaches`) and resolves it from `LudwigCacheRegistry`; the deployment configures it under
  `ludwig.cache.caches.<name>`. **The one thing a module must get right is `CachePurpose`**: `security`
  means the TTL is how long a revoked grant keeps working, and it gets a startup ceiling and no stale reads;
  `performance` means the TTL is a throughput knob, and it gets minutes, stale-while-revalidate and the load
  lease. That declaration is not inferable and is the only mistake the module cannot detect for you. An
  ArchUnit rule (`RuleGroup.CACHING`) fails the build on the builder and on a second SPI, with one exemption
  named in the rule: `export`'s `EnrichmentCache` is scoped to one report run, so sharing it would be a
  correctness change rather than a consolidation. `cache-spring-boot-starter` has **zero in-repo
  dependencies** on purpose, which is what lets `security-spring-boot-starter` depend on it without a
  reactor cycle; `ModuleIndependenceTest` fails the build if that is ever broken.
- **Caller preferences go through `web-core`'s one contract.** `ru.ludwigandreas.webcore.preference` owns
  the caller's locale and zone: `UserPreferences` (two dimensions, with everything derivable from them a
  method on it rather than a field beside it), the `UserPreferenceSource` SPI, the ambient
  `UserPreferences.current()`, the `bind()` scope for a worker thread, and `UserPreferenceFormatter`,
  which a mapper names in `@Mapper(uses = ...)` so MapStruct selects the conversions by type with no
  signature change. A module must not call `Locale.getDefault()`, `TimeZone.getDefault()`,
  `ZoneId.systemDefault()` or `Clock.systemDefaultZone()` - the JVM default is the container's, which is
  UTC in the datacentre and the developer's own zone on a laptop, so the defect is invisible exactly
  where it would be caught - and must not declare a second type holding the pair. Both are failed by
  ArchUnit's `RuleGroup.PRESENTATION`. **There is deliberately no preference storage here**: as with the
  operation contract, `web-core` stays free of any persistence dependency, the SPI is declared there and
  implemented in `user-settings-spring-boot-starter`, whose `StoredUserPreferenceSource` answers only for
  a `SettingLayer` more specific than `PLATFORM` - a setting nobody has touched resolves from the
  `DEFAULT` layer, and answering from that would make every caller UTC and make the header and
  configuration sources unreachable. The *caller's* ambient preferences and a *subject's* stored
  preferences are different things and must stay so: notification's `RecipientPreferences` is resolved
  per recipient on a worker with no caller, the restatement rule is written not to fire on that shape,
  and its javadoc states the distinction because the distinction is the rule.
- Services follow the reference shape in `crud-service-example`: three model layers
  `web.dto` → `service.model` → `repository.entity`, wired by MapStruct; Lombok instead of
  boilerplate; **QueryDSL-JPA against generated Q-types only** — no JPQL/SQL strings, no derived
  query methods; transactions in the service layer; Liquibase changelogs under
  `src/main/resources/db/changelog`, in the one shape the next bullet describes.
- **Every Liquibase changeset is formatted SQL, and the layout is uniform.** A changeset lives in
  its own `NNNN-<slug>.sql` file opening with `--liquibase formatted sql`; its id is
  `<prefix>-NNNN-<slug>` and its author `ludwig-<prefix>`, with `NNNN` and `<slug>` identical to the
  filename's, so the tree and `DATABASECHANGELOG` cannot disagree. Numbers are unique per module and
  ascend in include order. Every changeset declares `dbms:postgresql` and a `--rollback` — real SQL,
  or `--rollback NOT REQUIRED`, which parses to the same empty rollback as XML's `<rollback/>`; the
  point is that the author decided. Each module keeps exactly one **XML root changelog containing
  `<include>` elements and comments only**, pinned to `dbchangelog-4.27.xsd`, which stays XML only
  because Liquibase's formatted-SQL parser has no include directive. Preconditions are limited to
  `--precondition-table-exists table:<name>`, `--precondition-view-exists` and
  `--precondition-sql-check` — the only three that parser implements. Prose comments must never begin
  a line with a directive name (`changeset`, `rollback`, `preconditions`, …): before the first
  changeset Liquibase rejects the whole file over it, and after it the line is silently read as
  prose. `scripts/check_migrations.sh` enforces all of it and the gate runs it; it is a gate script
  rather than an ArchUnit or Checkstyle rule because a changelog is a resource. The QueryDSL-only
  rule above governs repository queries in Java and does **not** apply here. The spec is the
  `database-migration` capability in `openspec/specs/`.
- Every container image reference is pinned by name, version **and** `sha256` digest. A bare tag is
  not acceptable anywhere, including in READMEs and docker run examples.
- **Two carve-outs from the QueryDSL-only rule exist, and each has a boundary.**
  `ru.ludwigandreas.ingest.bulk` in `file-ingest-spring-boot-starter` may contain SQL strings, because
  two statements cannot be written in QueryDSL and both are load-bearing: Postgres `COPY` (via
  `CopyManager`, which streams and is several times faster than batched `INSERT`) and the set-based
  `INSERT … SELECT … ON CONFLICT DO UPDATE` that merges a staging table into a target. The
  alternative is reading four million staged rows into the JVM to write them back one at a time,
  which is the behaviour that module exists to avoid. The conditions: every SQL string stays in that
  one package; each statement carries javadoc saying why QueryDSL cannot express it (the standard
  `IdempotencyRecordRepository` in notification-service met this for its native `@Query`
  before that class was promoted into `idempotency-spring-boot-starter`); `SqlConfinementTest` in that module fails the build if SQL or JDBC appears anywhere
  else in it. Nothing outside that package has this exception - the ingest
  module's own tables are queried through QueryDSL predicates like everything else.
- **The second carve-out is `ru.ludwigandreas.idempotency.sql` in `idempotency-spring-boot-starter`**, on the
  same conditions and enforced the same way by its own `SqlConfinementTest`. One statement: the conditional
  upsert that claims a key, `INSERT ... ON CONFLICT (scope, idempotency_key) DO UPDATE ... RETURNING`. JPQL
  has neither `ON CONFLICT` nor `RETURNING` and QueryDSL-JPA generates JPQL, and the alternatives are not
  merely less tidy but wrong: read-then-insert lets two replicas both insert and aborts a transaction that has
  already written real work, and `DO NOTHING` returns no row on conflict so the loser must re-select - which in
  `READ COMMITTED` can still miss a row whose inserting transaction has not committed. The reads, the three
  state transitions and the purge in that module are QueryDSL like everything else.

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
// SUPPRESS CHECKSTYLE ID SecondRedactionMask - reason      // next line, rule addressed by id
```

The last form exists because several checks share the `RegexpSinglelineJava` class name, so naming the
class would switch all of them off at once. Use it for any rule the config gives an `id`
(`NonAsciiSourceText`, `ConsoleOutput`, `SecondRedactionMask`, ...).

## Credentials

No credential appears in any POM. jib and `deploy` resolve them from `~/.m2/settings.xml` `<server>`
entries matching the registry host / `ludwig.repo.{releases,snapshots}.id`, from
`JIB_TARGET_USERNAME`/`JIB_TARGET_PASSWORD`, or from local Docker credential helpers.
