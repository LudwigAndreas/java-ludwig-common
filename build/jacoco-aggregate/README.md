# jacoco-aggregate

One coverage report over the whole platform.

```bash
mvn clean install                      # the full reactor; the report is written last
open build/jacoco-aggregate/target/site/jacoco-aggregate/index.html
```

XML and CSV are written beside the HTML, which is what a CI collector or SonarQube reads.

## What it is

A module with no sources, no tests and no published artifact. Its entire content is a dependency on
every jar module in the reactor, because `jacoco:report-aggregate` builds its report from **the
modules this project depends on**. The dependency list in `pom.xml` is therefore not boilerplate —
it is the definition of what "the platform's coverage" means.

`report-aggregate` is bound to `verify`. Maven orders the reactor from the dependency graph, so a
module that depends on everything is built last automatically; the report reads the execution data
each module wrote during the same build.

## The one thing that will go wrong

**Adding a module to the reactor does not add it to this report.** It has to be added to the
`<dependencies>` block here too. If it is not, the aggregate quietly omits it — the build stays
green, the report renders, and the number is wrong by however much that module contributes.

Nothing currently catches that. The check that would is a comparison between this POM's
dependencies and the jar modules in `project-index.json`; it does not exist yet and is recorded as
a gap in the `repository-layout` capability spec. Until it does, treat "add the module to
`build/jacoco-aggregate/pom.xml`" as part of adding a module, the same way "add it to the root
POM's `<modules>`" already is.

## Why the instrumentation lives in the root POM

It did not always. JaCoCo used to sit in the reactor root's `<pluginManagement>` only, which meant
each module re-declared the plugin to be measured. Twenty modules did. Five did not —
`object-storage-spring-boot-starter`, `file-ingest-spring-boot-starter`, `test-support`,
`test-support-security` and `checkstyle-rules` — and nothing failed, because a module that is not
instrumented looks exactly like a module with no code.

An aggregate built on that would have under-counted the platform by five modules and still been
green, which is the failure this report exists to avoid rather than to demonstrate. So the plugin
is now declared in the root POM's active `<build><plugins>`, next to Checkstyle and for the reason
already written there: *so that every module, and every module added later, is measured without
anyone having to remember to opt in.*

Services do not inherit that. They are parented by `ludwig-service-parent`, which configures its
own merged-exec report and the **enforced** thresholds (70% instruction, 60% branch). This module
reads their merged data and does not change any of it.

## What it does not do

**It enforces nothing.** There is no platform-wide threshold here, deliberately. A number chosen
today would fail the build today, and the response to a gate that fails on the day it is introduced
is to lower it until it passes — which measures nothing and teaches nobody anything. The per-service
gate in `ludwig-service-parent` still enforces; this report informs.

Adding a platform threshold is a reasonable future change. It should be its own change, with a
number argued from what the report actually says.

## Included modules, and why test-support is among them

All 27 jar modules, including `test-support`, `test-support-security`, `checkstyle-rules` and
`architecture-rules`. They are shipped code that other modules depend on, and excluding a module
from the denominator because its coverage is inconvenient is how an aggregate stops meaning
anything.

The two POM-packaged modules — `ludwig-bom` and `ludwig-service-parent` — are absent. They contain
no classes, so there is nothing to measure.

If a module genuinely should be excluded, use the plugin's `<excludes>` on class patterns rather
than dropping the dependency: dropping it removes the module from the report entirely and leaves no
trace that it was ever considered.

## Execution data

`dataFileIncludes` is left at its default, which is every `*.exec` under each dependency's
`target/`. Library modules write one file (`jacoco.exec`); services write three (`jacoco.exec`,
`jacoco-it.exec`, and the `jacoco-merged.exec` that `ludwig-service-parent` merges from the first
two).

Reading all three is not double counting. JaCoCo merges execution data by class id and ORs the
probe arrays, so a probe recorded in both `jacoco.exec` and `jacoco-merged.exec` is one covered
probe, not two. An explicit include list would buy nothing and would then have to be kept in step
with `ludwig-service-parent`'s merge configuration for as long as both exist.
