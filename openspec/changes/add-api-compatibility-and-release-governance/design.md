## Context

See `proposal.md` — Why. The constraints this design has to fit inside, all of them pre-existing:

- **`${revision}` and `flatten-maven-plugin` already work**, and this change must not disturb them.
  `-Drevision` arriving as a *user property* is what lets it reach `ludwig-service-parent`, whose
  parent is `spring-boot-starter-parent` and which therefore inherits no property from the reactor
  root. GitVersion plugs into exactly that seam and nothing else has to move.
- **The reactor root is the parent of libraries only**; the two services inherit
  `ludwig-service-parent`. Anything declared in the root POM's `<build>` reaches the 25 jar libraries
  and cannot reach a service. This change uses that as its scoping mechanism rather than an exclude
  list, which would need maintaining.
- **There are zero tags and nothing published**, so the baseline has to be created before any gate
  can be meaningful. The user's decision is to seed it from current HEAD as `v1.1.0`.
- **An agent may not run `deploy`, `jib:build` or `-Pci`** (`.claude/settings.json` denies all four
  patterns). Seeding the baseline is therefore an operator step, and the task list marks it as one.
- **`mvn clean install` is the gate** for this change, because the root POM and
  `ludwig-service-parent` both change and `scripts/gate.sh` substitutes the full build in that case.

**Dependency-direction check.** *Does this change add any in-repo dependency, and if so, does the
target already depend on the source?* It adds **none**. revapi, `maven-javadoc-plugin`,
`maven-jar-plugin` and `maven-enforcer-plugin` are build plugins and their extensions are plugin
dependencies, which Maven resolves on the plugin classpath and which do not enter the reactor DAG.
`architecture-rules`'s POM is edited to inherit a managed plugin version and gains no `<dependency>`
entry; its `inRepoDependents` (6) and `inRepoDependencies` are unchanged. No module's
`inRepoDependencies` changes, so the DAG is identical before and after and no cycle is possible.

**Which of the three POMs changes.**

| POM | Changes | Why there |
|---|---|---|
| `pom.xml` (root, artifact `common`) | revapi plugin + its extension versions and configuration; `maven-javadoc-plugin` pinned and attached under `-Pci`; `maven-jar-plugin` pinned; `requirePluginVersions` added to the enforcer; the `-Prelease` changelog and fallback-version rules | Plugin versions and build configuration for libraries live here, and declaring the API gate here is what keeps it off the services |
| `ludwig-service-parent` | `requirePluginVersions` added to its existing enforcer execution, and nothing else | Services inherit their build decisions here. They deliberately get no revapi and no Javadoc jar |
| `ludwig-bom` | **no change** | It holds third-party *dependency* versions. A plugin's version and a plugin's extension versions are plugin configuration, which `pom-topology` assigns to the root POM |

## Goals / Non-Goals

**Goals:**

- One place decides the version (the git history), one place decides whether an API change is
  allowed (the version increment), and the second reads the first.
- Every rule this change introduces either fails a build or carries a comment saying why it cannot.
- The local loop stays as fast as it is today: nothing added here runs before `verify`, except the
  two `validate`-phase checks that only read files.

**Non-Goals (design level, beyond the proposal's):**

- No attempt to make the gate work offline against an unfetched baseline. An unresolvable baseline is
  a failure, not a skip, and the documented escape is a command-line `-Drevapi.skip=true`.
- No per-module `revapi.json` files. Configuration is inherited from the root POM; a module gets its
  own only when it needs a justified exemption.
- ~~No delombok step for Javadoc.~~ **Amended during implementation, on the user's decision.** The
  original reasoning was about *documenting* Lombok-generated accessors, and it does not survive
  contact with the requirement: four public methods in two modules declare a Lombok-`@Builder`
  generated type as their **return type** - `export`'s `ReportDefinition.of`, `Column.of` and
  `EnrichmentStage.of`, and `user-settings`' `SettingDefinition.of`. Those types exist in bytecode
  and never in source, so Javadoc fails with `cannot find symbol`, which is a javac error rather
  than a doclint category: no `-Xdoclint` group excludes it, and only `<failOnError>false</failOnError>`
  would silence it - which would also silence the broken-`{@link}` gate this change exists to add.
  Three alternatives were measured and all three fail: Javadoc refuses `-processorpath` outright
  (`error: invalid flag`), so it runs no annotation processors; a second class-path option replaces
  the first rather than extending it; and prepending `target/classes` to the single classpath does
  not help, because javac prefers the source on the sourcepath over the class file and the nested
  builder is not in that source. `published-artifact-set` **requires** a `-javadoc` jar from every
  jar module, so the mechanism, not the requirement, gives way. `lombok-maven-plugin`'s `delombok`
  goal runs at `generate-sources` **in the `ci` profile only**, with `addOutputDirectory=false` so
  nothing compiles from the copy, and `maven-javadoc-plugin`'s `sourcepath` points at the
  delomboked tree plus `generated-sources/annotations` - the second entry is not optional, because
  QueryDSL's Q-types are real source that several modules name in a signature.

## Decisions

### 1. revapi over japicmp, and why a fourth tool does not break the enforcement triad

revapi is chosen because it does the two things japicmp does not: it has a **semver extension** that
maps a version increment onto the severity of change it permits, which is the whole mechanism this
change needs; and its difference filtering is configurable per difference with a recorded
justification, which is how a deliberate removal gets reviewed rather than silently allowed.

The `enforcement-triad` capability says a check belongs to exactly one of ArchUnit (structure),
Checkstyle (source text) or SonarQube (bugs and security). revapi is a fourth tool and does not
overlap any of them, because **it is the only one that reads two versions of the code.** ArchUnit
analyses the classes on the current classpath; it cannot see the previous release, so
"`AuditSink.record` existed in 1.2.0" is not a statement it can make. The boundary this design adds:
*a rule about how the API differs from a published baseline belongs to revapi, and ArchUnit must not
grow a rule about API removal.* That sentence goes into `architecture-rules`' rule-group javadoc, per
the repository's own habit of recording a tool's boundary where somebody would otherwise cross it.

### 2. The version increment is the permission, and the baseline is `RELEASE`

`revapi-maven-plugin` runs at `verify`, with `oldVersion` resolving the newest release of the same
coordinates from `ludwig.repo.releases` and `newVersion` being the module just built. The semver
extension is configured so that a **major** increment permits a breaking difference, a **minor**
permits a non-breaking one, and a **patch** permits only an equivalent API:

```json
{"revapi": {"semver": {"ignore": {
  "enabled": true,
  "versionIncreaseAllows": {"major": "breaking", "minor": "nonBreaking", "patch": "equivalent"}
}}}}
```

This is the join between the two halves of the change: GitVersion decides the increment from the
history, revapi decides what that increment buys. Note the asymmetry, which is deliberate and is
stated in the spec: the gate refuses an increment that is **too small** and can never refuse one that
is too large. A team that bumps the major every release makes the gate vacuous, and no tool can see
that — only the changelog can.

*Alternative considered:* configuring a flat `failSeverity` and relying on humans to bump. That is
what most projects do and it is exactly the current situation one step further along: the build tells
you the change is breaking and then you decide, at the moment you least want to be told.

### 3. A removal at a major increment still needs a justified exemption

The specs require that an element only be removed if the **baseline** already carried
`@Deprecated(forRemoval = true)`. The mechanism: the removal difference is not silenced by the semver
rule alone — it must also be listed in `revapi.differences` with a `justification` that names the
release which deprecated it, or the build fails.

```json
{"revapi": {"differences": {"differences": [
  {"code": "java.method.removed",
   "old": "method void ru.ludwigandreas.audit.AuditSink::legacyRecord(...)",
   "justification": "Deprecated for removal in 1.4.0; removed in 2.0.0. Replacement: record(AuditEvent)."}
]}}}
```

This is stricter than an annotation matcher and, unlike one, it is certain to be expressible: every
removal leaves a reviewable line in a file, and a removal nobody wrote a line for fails the build.
If the plugin version in use supports filtering by the baseline's annotations, that can replace the
per-difference entry later as an optimisation; the task list verifies the option names against the
plugin actually resolved rather than trusting this document.

*Trade-off:* the exemption list grows across majors and has to be pruned when the baseline moves past
a removal. That pruning is visible work on a file, which is better than the alternative of an
invisible allowance.

### 4. GitVersion runs in a digest-pinned container, and the pipeline — not GitVersion — appends `-SNAPSHOT`

`gitversion.yml` at the repository root configures `mode: ContinuousDelivery` and the branch
increments. The pipeline reads **`MajorMinorPatch`** and decides the suffix itself:

- HEAD carries a tag → `MajorMinorPatch` verbatim; this is a release build and the only kind that
  gets `-Prelease`.
- HEAD carries no tag, on any branch → `MajorMinorPatch-SNAPSHOT`.

GitVersion's own pre-release labels (`1.3.0-alpha.4`) are deliberately not used: they are valid SemVer
and **not** Maven snapshots, so they would be published to the releases repository as immutable
artifacts and would satisfy `requireReleaseDeps` in a consumer. Maven's two-repository model is the
constraint, and a label that looks like a pre-release to humans and like a release to Maven is the
worst of both.

The tool runs as `gittools/gitversion:<version>@sha256:<digest>` over a bind-mounted checkout, so the
agent needs no .NET runtime, and the pin satisfies `container-image-pinning` rather than asking for an
exception. GitVersion needs the full history and the tags, so the checkout stage drops `shallow` and
fetches tags; without that it fails, which the spec requires rather than letting it compute a version
from a truncated history.

*Alternative considered:* a Maven extension (`maven-git-versioning-extension`, `jgitver`) deriving the
version inside Maven. It needs no external tool and works identically on a laptop — but it makes every
local build's version depend on git state, which is a change to how `${revision}` behaves for
everyone, and the user chose the CLI.

### 5. Every new rule, and the tool that owns it

| Rule | Mechanism | Owner | Phase |
|---|---|---|---|
| A library's API may not break below a major increment | `revapi-maven-plugin` + semver extension | revapi (fourth tool; boundary stated in Decision 1) | `verify` |
| A removal needs a justified exemption | `revapi.differences` entry, absent ⇒ failure | revapi | `verify` |
| `@Deprecated` carries `since` and `forRemoval` | Checkstyle `RegexpSinglelineJava`, id `DeprecationWithoutSince` | `checkstyle-rules` (source text) | `validate` |
| `@Deprecated` has a Javadoc `@deprecated` tag | Checkstyle `MissingDeprecated` | `checkstyle-rules` | `validate` |
| Every plugin carries a version | `maven-enforcer-plugin` `requirePluginVersions` | enforcer (build integrity — none of the three tools sees a POM) | `validate` |
| A release has a changelog heading in both locales | enforcer `evaluateBeanshell` in the existing `-Prelease` execution | enforcer | `validate`, `-Prelease` only |
| `.mvn/maven.config` holds only a SNAPSHOT fallback | the same `evaluateBeanshell` rule, unconditionally | enforcer | `validate` |
| Javadoc is well-formed | `maven-javadoc-plugin` `-Xdoclint:all,-missing` | javadoc plugin | `package`, `-Pci` only |
| A deprecated API survives one minor before removal | **none possible** — one build compared against one baseline cannot see how many releases separate them. Recorded as a comment beside the revapi configuration | — | — |

Two notes on the Checkstyle rule, both of which belong in the config beside it:

- The `DeprecationWithoutSince` regexp matches `@Deprecated` on one line and requires `since` among
  its members. Written as a single-line regexp it cannot see an annotation whose members are split
  across lines. That is acceptable **because** formatting mirrors IntelliJ's defaults, which keep
  annotation members on the declaration line — and the limit is written next to the rule so the next
  reader knows the hole exists rather than discovering it.
- It uses the `id` suppression form (`// SUPPRESS CHECKSTYLE ID DeprecationWithoutSince - reason`),
  because `RegexpSinglelineJava` is shared by several rules and naming the class switches all of them
  off.

### 6. Javadoc: correctness gated, completeness left alone

`maven-javadoc-plugin` is pinned in the root POM's `pluginManagement` and attached in the **`ci`
profile only**, beside the existing `maven-source-plugin` execution, with `doclint` set to
`all,-missing`, `detectJavaApiLink` off (so a build without network access does not try to resolve
`java.base` links) and `quiet` on. The effect: a broken `{@link}` fails CI, and the 288 missing-Javadoc
warnings stay exactly where they are as the existing Checkstyle warning tier. Gating completeness here
would make the change unmergeable, which is the reason it is a stated non-goal rather than an
oversight.

### 7. One changelog pair, at the root

Every module shares one `${revision}` and releases together, so 30 changelogs would be 30 copies of
one list. `CHANGELOG.md` and `CHANGELOG.ru.md` live at the root in Keep-a-Changelog form with an
`## [Unreleased]` section. The per-change entry is written when a change is **archived**, which is
where `openspec/config.yaml` already puts "update the READMEs in both locales" — so the guidance goes
next to it. That part is guide-only, and deliberately: whether an entry describes the change
accurately is not mechanisable. What *is* mechanised is that a release cannot happen with no entry at
all, which is the failure that actually occurs.

## Risks / Trade-offs

- **The seeded baseline freezes today's API, warts included.** → Accepted on the user's decision. The
  mitigation is that `v1.1.0` is tagged from a known-green `mvn clean install`, and anything that
  should have changed first can still change — at a major, with an exemption entry, which is the
  process working rather than failing.
- **The first gated build could fail for reasons that have nothing to do with a real break** (revapi
  reporting differences between a module and a copy of itself, usually from annotation or generic
  signature handling). → The migration plan makes "run revapi against the just-published baseline with
  no source change and get zero differences" an explicit acceptance step, before any gate is switched
  to failing.
- **`requirePluginVersions` is strict** and can fail on plugins inherited from Maven's super-POM
  rather than on anything this repository wrote. → The task that adds it runs `mvn -q validate` across
  the reactor as its verification; if the rule reports super-POM plugins, the fallback is
  `<unCheckedPluginList>` naming them with a comment, and if that proves unworkable the rule is
  replaced by a grep over `**/pom.xml` in `scripts/gate.sh`. The outcome is recorded either way —
  never dropped quietly.
- **GitVersion is a new external tool in the critical path of every build.** → It runs in one stage; if
  it fails, the build fails before compiling anything, and the failure names the tool. A developer
  never needs it: `.mvn/maven.config` is the fallback and `mvn clean install` works with no network.
- **`ContinuousDelivery` mode plus tag-driven releases means a branch can compute a version that a
  later merge changes.** → Only tagged builds publish releases, so a recomputed SNAPSHOT is harmless.
- **The image-pin rule currently has no check outside `test-support`'s `images.properties`.** Adding a
  CI tool image would widen an existing hole. → A small `scripts/check_image_pins.sh`, wired into
  `scripts/gate.sh`, greps `Jenkinsfile`, YAML and Markdown for `image:`-style references with no
  digest. This also closes the hole for the k8s manifest and the READMEs, which are unchecked today.

## Migration Plan

1. **Land the mechanism, inert.** revapi configured with the semver extension but with
   `failBuildOnProblemsFound` off; Javadoc, the pinned plugins, `requirePluginVersions`, the Checkstyle
   deprecation rules, the changelogs, `gitversion.yml` and the Jenkinsfile stages all land here. A full
   `mvn clean install` must pass. Nothing is published, so no gate can be meaningful yet.
2. **Operator step — seed the baseline.** A human tags `v1.1.0` on the merge commit and runs
   `mvn -Prelease -Pci deploy` (an agent may not: `-Pci` and `deploy` are both denied). This publishes
   the jars, the flattened POMs, the sources jars and the new Javadoc jars as the compatibility floor.
3. **Acceptance.** Re-run `mvn clean install` with revapi still non-failing and confirm **zero**
   differences against the freshly published `1.1.0`. Any difference reported here is a configuration
   artefact, not a real one, and is fixed before step 4.
4. **Arm the gate.** Flip `failBuildOnProblemsFound` on. From this point a breaking change below a
   major fails.
5. **Rollback.** Each step is a revert of one POM block; the tag and the published `1.1.0` can stay
   whether or not the gate is armed, because an unread baseline harms nothing. Reverting step 1 restores
   today's build exactly, since nothing outside `<build>`, `Jenkinsfile` and two new root-level files
   changed.

## Open Questions

- The exact revapi option names for baseline resolution and for the missing-baseline case
  (`oldVersion` vs `oldArtifacts`, and which option distinguishes *absent* from *unresolvable*) are
  verified against the resolved plugin version by the task that adds it, not asserted here. The
  **behaviour** is specified — absent is a logged non-comparison, unresolvable is a failure — and a
  plugin that cannot express the distinction gets a wrapper check in `scripts/gate.sh` rather than a
  weakened requirement.
- Whether `test-support` and `test-support-security` should carry the same gate as a runtime library.
  They are gated here, because a consumer's test suite breaks on a removed fixture exactly as their
  main code breaks on a removed method. If that proves noisy in practice it is a per-module exemption,
  not a change to the requirement.
