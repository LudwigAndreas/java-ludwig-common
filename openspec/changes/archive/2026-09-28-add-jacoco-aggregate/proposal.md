## Why

There is no whole-repository coverage number. Every module reports its own, and the only enforced
gate (70% instruction / 60% branch, in `ludwig-service-parent`) applies to the two services. Nobody
can answer "what is the platform's coverage" without opening 29 reports and doing arithmetic.

Worse, the instrumentation is not even uniform. **Five jar modules run no JaCoCo at all** —
`object-storage-spring-boot-starter`, `file-ingest-spring-boot-starter`, `test-support`,
`test-support-security` and `checkstyle-rules` — because JaCoCo sits in the root POM's
`<pluginManagement>` and each module has to opt in by re-declaring it. Twenty modules remembered;
five did not, and nothing noticed. An aggregate built on that would silently under-report.

## What Changes

1. **JaCoCo is enabled once, for everything.** The plugin moves from the root POM's
   `pluginManagement` into its active `<build><plugins>`, so every module parented by the reactor
   root inherits it. The 20 bare re-declarations in individual module POMs are deleted — they
   become exactly redundant. Services are unaffected: they inherit their own gated JaCoCo
   configuration from `ludwig-service-parent`.
2. **A new module, `build/jacoco-aggregate`.** Packaging `pom`, no sources. It declares a
   dependency on each of the 27 jar modules and binds `jacoco:report-aggregate` to `verify`,
   producing one HTML/XML/CSV report covering the whole platform.

## Non-goals

- **Enforcing a repository-wide coverage threshold.** Adding one here would fail the build on day
  one and the response would be to lower it until it passed, which teaches nobody anything. The
  aggregate reports; the existing per-service gate still enforces. A platform threshold is a
  separate decision with a separate conversation.
- Changing any module's own coverage configuration or thresholds.
- Publishing the aggregate. It is a build artifact, not something a consumer resolves.
- Writing any test. This change adds no coverage; it measures the coverage that exists.

## Shared contracts touched

`repository-layout` gains no new requirement, but the new module needs a role that maps to
`build/`, so the manifest generator's classification and the layout check are extended. No other
spec in `openspec/specs/` changes.

## Impact

| Module | POM tier | Note |
|---|---|---|
| `jacoco-aggregate` (new) | library tier — parented by the reactor root `common`, imports `ludwig-bom` | lives in `build/`, packaging `pom` |
| root `pom.xml` | — | JaCoCo becomes an active plugin; one `<module>` entry added |
| 20 library modules | library | a redundant `<plugin>` block removed from each |

The aggregator depends on all 27 jar modules, so **every module gains it as an in-repo dependent**.
That would make the verification gate for every module a full-reactor build, which defeats the
purpose of a narrow gate — so the manifest excludes it from `inRepoDependents` and says why. Nothing
depends on the aggregator, so no cycle is possible.

Because the root POM changes, the gate is the whole reactor: `mvn clean install`.
