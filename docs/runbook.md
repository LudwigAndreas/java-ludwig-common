# Runbook — developing and releasing `ludwig-common`

> For a person. `docs/agent-operations.md` is the same ground for an agent and is stricter; if the
> two ever disagree, that is a bug in one of them — say so rather than picking.

This file answers four questions and nothing else: **how do I add a feature, how do I fix a bug, how
does a version get decided, and how does a release happen.** Everything about *what the code must
look like* is in `CLAUDE.md` and the module READMEs, and is not repeated here.

---

## 0. The five things that surprise people

Read these before anything else. Each one has bitten somebody.

1. **A release is a tag.** Nobody edits a version anywhere. `.mvn/maven.config` is a fallback for a
   checkout with no tags, it must stay a `-SNAPSHOT`, an enforcer rule fails the build if it is not,
   and a hook refuses to edit the file at all.
2. **No code without an OpenSpec change.** Not a convention — the workflow the repository is built
   around. Section 2.
3. **An integration test must be named `…IT` or `…IntegrationTest`, or it never runs.** Surefire
   excludes those names and failsafe runs them at `verify`. A test called `FooTest` that needs
   Postgres runs under surefire and fails on a machine with no Docker.
4. **Select modules as `-pl :artifactId`, never by directory.** Directories moved once already and
   every path-based command broke.
5. **The API of a published library is frozen against the last release.** Removing a public method is
   a build failure unless the version increment permits it *and* the previous release warned about
   it. Section 5.

---

## 1. Setting up

| Need | Why |
|---|---|
| JDK 17 | `maven.compiler.release=17`. A newer JDK on `JAVA_HOME` mostly works; Lombok and Byte Buddy are pinned ahead of Spring Boot's line precisely so it does |
| Maven 3.9+ | `requireMavenVersion` enforces it for services |
| **Docker, running** | Every integration test is Testcontainers-backed. Without a daemon they do not skip — they *error*, and `mvn clean install` fails on `test-support` |
| Nothing else | No IDE code style to import: the Checkstyle rules mirror IntelliJ's out-of-the-box defaults, so Reformat Code always produces a build-clean file |

```bash
git clone <repo> && cd java-ludwig-common
mvn clean install          # ~full reactor, integration tests included
```

If that is green you are set up. If it fails on Testcontainers, start Docker — it is not optional
here.

> **One caveat about reformatting.** `Reformat Code` on `pom.xml` is safe *except* for
> `<propertyExpansion>`, where a line break is semantic. Both occurrences are written as `&#10;`
> with a comment saying why. If you ever see
> `No enum constant …SeverityLevel.WARNING PACKAGE.INFO.SEVERITY=IGNORE`, that is what happened.

---

## 2. Adding a feature

### 2.1 Branch

```bash
git switch develop && git pull
git switch -c feature/short-description
```

`feature/*` and `feature-*` are both recognised by `gitversion.yml`. The branch name has no effect on
the released version; it only decides how the SNAPSHOT is numbered while you work.

### 2.2 Write the change before the code

```bash
openspec new change add-short-description
```

Artifacts, in this order — the CLI will tell you which is next:

| Artifact | When it is required | What it is |
|---|---|---|
| `proposal.md` | always | Why, what changes, which capabilities, impact |
| `design.md` | when the change adds a module, crosses a module boundary, or touches a shared contract | Decisions and their alternatives. The place to record *why not* |
| `specs/<capability>/spec.md` | always | Requirements and scenarios, as deltas against `openspec/specs/` |
| `tasks.md` | always | The implementation list, each task with its own verification command |

```bash
openspec validate add-short-description     # before calling it ready
```

**Why this exists rather than just writing the code:** the spec is an independent check on the
implementation. If the same person writes both in one pass, it stops being one. The cost is real and
so is the benefit; do not skip it for "a small change" — small changes are where unreviewed scope
creep lives.

### 2.3 Where the code goes

| If it is… | Directory | Parent POM | Also |
|---|---|---|---|
| a library or starter | `sources/<name>/` | the reactor root `common` | import `ludwig-bom`; declare `<relativePath>../../pom.xml</relativePath>` |
| a runnable service | `services/<name>/` | `ludwig-service-parent` | ships as an image |
| build tooling | `build/<name>/` | the reactor root | `checkstyle-rules`, `architecture-rules`, the BOM, the parent |

`<relativePath>` is not optional. Maven's default is `../pom.xml`, which from one level down is a
directory with no POM — and Maven does not fail, it silently resolves the parent from `~/.m2` and
builds against a stale `common`.

`scripts/manifest.sh layout` fails if a module is in the wrong directory for its role, and the gate
runs it.

### 2.4 Before you add a dependency between modules

Answer out loud: *does the module I am depending on already depend on mine, directly or
transitively?*

```bash
scripts/manifest.sh module sources/<your-module>   # both directions
```

The reactor DAG must stay acyclic. `cache-spring-boot-starter` has **zero** in-repo dependencies on
purpose, which is what lets `security-spring-boot-starter` use it; `ModuleIndependenceTest` fails the
build if that is broken.

### 2.5 Run the gate

```bash
scripts/gate.sh --change add-short-description <your-module>
```

It works out the in-repo **dependents** for you and builds them too. That direction is the one that
catches a breaking change, and it is the one people skip — `-am` builds a module's *dependencies*,
which is the opposite. `scripts/gate.sh --list <module>` prints the commands without running them.

A change to `ludwig-bom` or `ludwig-service-parent` has no narrower gate than `mvn clean install`,
and the script substitutes it rather than letting you run a partial one.

### 2.6 Open the PR

Target `develop`. On merge, IFT redeploys automatically (section 4).

---

## 3. Fixing a bug

Identical to section 2 with two differences:

- Branch from `develop` as `fix/…` unless it has to ship **now** without waiting for the next
  release — that is a hotfix, section 6.
- The change still needs an OpenSpec entry. A one-line fix gets a one-line proposal; what it must not
  get is nothing, because a fix with no recorded reason is the one that gets reverted by the next
  person who reads the line and thinks it looks wrong.

**Write the failing test first, in the same change.** A fix with no test is a fix that returns.

---

## 4. Versions, branches and IFT

### 4.1 Nobody types a version

GitVersion reads the git history and the pipeline passes the answer to Maven as `-Drevision=…`. The
pipeline, not GitVersion, decides the suffix:

| HEAD | Version | Goes to |
|---|---|---|
| carries a tag | `1.3.0` | `ludwig.repo.releases`, and prod |
| carries no tag, any branch | `1.3.0-SNAPSHOT` | `ludwig.repo.snapshots` |

GitVersion's own pre-release labels (`1.3.0-alpha.4`) are deliberately switched off on every branch.
They are valid SemVer and **not** Maven snapshots, so Maven would publish them to the releases
repository as immutable artifacts and they would satisfy `requireReleaseDeps` in a consumer — a label
that reads as a pre-release to a human and as a release to Maven is the worst of both.

### 4.2 What each branch computes

From `gitversion.yml`:

| Branch | Increment | After `v1.2.0`, an untagged commit reads |
|---|---|---|
| `master` / `main` | Patch | `1.2.1-SNAPSHOT` |
| `develop` | Minor | `1.3.0-SNAPSHOT` |
| `feature/*` | Inherit from source | whatever its source branch would give |
| `hotfix/*` | Patch | `1.2.1-SNAPSHOT` |
| `release/*` | None | holds the number it was cut with |

`release/*` is configured and **the process below does not use it.** It is there so that adopting a
stabilisation branch later needs no config change.

### 4.3 Asking for a bigger bump

The branch increment is the default. To ask for more, put a marker in a commit message — these are
GitVersion's defaults and this repository does not override them:

```
+semver: major      (or "breaking")
+semver: minor      (or "feature")
+semver: patch      (or "fix")
+semver: none       (or "skip")
```

**This only affects the SNAPSHOT number.** On a tagged build the tag *is* the version. So the marker's
real job is to keep the develop SNAPSHOT honest between releases — which matters, because that is the
number the API gate checks your change against (section 5).

> GitVersion itself has been configured and schema-validated but **never executed** against this
> repository — there was no Docker on the machine `gitversion.yml` was written on. The first
> pipeline run is also the first real test of it. If the computed number looks wrong, that is the
> place to look, not the Maven side.

### 4.4 IFT

**IFT runs `develop`.** Every merge to `develop` builds `x.y.z-SNAPSHOT` and redeploys IFT. There is
no release branch and no release candidate.

> ### ⚠ This part is the decided process and is NOT yet wired
>
> As the `Jenkinsfile` stands today, `develop` builds and tests and then stops. Both
> `Publish artifacts` and `Push image` carry `when { branch 'master' }`, so a develop build publishes
> no snapshot jar and pushes no image — and there is no IFT deploy stage at all.
>
> Two concrete changes make the rest of this section true:
>
> 1. widen those two stages to `when { anyOf { branch 'master'; branch 'develop' } }`, so a develop
>    merge produces the `-SNAPSHOT` artifacts and a snapshot image;
> 2. add a `Deploy to IFT` stage, `when { branch 'develop' }`, that rolls the image the previous
>    stage just pushed.
>
> Until both exist, IFT is deployed by hand and "every merge redeploys IFT" describes the intent
> rather than the pipeline. This note stays here until the stages land.

What this buys: one place to look, no branch to keep in sync, and feedback on the merged result
within one build.

What it costs, stated plainly so nobody is surprised by it: **IFT never tests the exact artifact prod
runs.** It tests the commit. Between IFT sign-off and the tag, `develop` can move. If that gap ever
matters more than the simplicity does, the answer is the `release/*` branch that `gitversion.yml`
already configures — it is a process change, not a tooling change.

---

## 5. What you may and may not change about a published API

This is the part with teeth, and the part that fails builds for people who have not read it.

### 5.1 The rule

Every jar parented by the reactor root is compared at `verify` against the newest release of the same
coordinates. **The version increment is the permission:**

| Increment | Permits |
|---|---|
| major | a binary-breaking difference |
| minor | a source-incompatible but binary-compatible difference |
| patch | an equivalent API only |

So removing a public method from `audit-core` on a patch fails, and the failure names the method and
the increment that would allow it.

Note the asymmetry: the gate refuses an increment that is **too small** and can never refuse one that
is too large. A team that bumps the major every release makes the gate vacuous, and no tool can see
that — only the changelog can.

### 5.2 Where you find out

On `develop`, not at release time. The develop SNAPSHOT is a minor bump, so a binary-breaking change
fails the develop build. If your change really is breaking, say so with `+semver: major` and the
build goes green — because you have now declared it, which is the whole point.

### 5.2a The HTTP surface is a different thing, guarded differently

Everything above is about **Java** API compatibility, and it applies to the jars - the libraries and
starters. A service's **HTTP** surface is not covered by it at all: revapi compares bytecode, and
nothing in a service's bytecode says that a JSON field disappeared from a response.

What guards that instead is a committed document per service, under `docs/api/`, generated from the
service's own Spring context by its `ApiDocumentIT` and compared at `verify`. The build fails when
the committed copy and the running service disagree, which means:

- **A pull request that changes a service's HTTP surface contains a diff of that surface.** This is
  the thing to actually read when reviewing one. A widened response, a dropped field, a changed
  status code and a new endpoint all appear there and nowhere else.
- **A refresh is deliberate.** `mvn -pl :<artifactId> verify -Dit.test=ApiDocumentIT
  -DfailIfNoTests=false -Dludwig.apidocs.write=true`, and then read what it produced. An ordinary
  build never rewrites the file, so `git status` staying clean is meaningful.

Note what this does **not** do: it does not refuse a backwards-incompatible change the way the
version increment above refuses one. There is no versioning policy for HTTP surfaces in this
repository, so the document makes the change visible and leaves the judgement to a person.
`docs/api/README.md` says the same thing at more length.

### 5.3 Deprecating something

```java
/**
 * …
 *
 * @deprecated use {@link Replacement#doThing()} instead
 */
@Deprecated(since = "1.3.0", forRemoval = false)
public void oldThing() { … }
```

Both members are mandatory and Checkstyle fails `validate` without them:

- `since` — a consumer reading a bare `@Deprecated` cannot tell whether they have one release or five.
- `forRemoval` — the compiler's removal warning never fires without it, so `false` is a statement,
  not a default.
- the Javadoc `@deprecated` tag must **name the replacement**, or say there is none. A deprecation
  that does not say what to use instead moves the problem to whoever reads it.

### 5.4 Removing something

Three conditions, all of them:

1. The **baseline release already carried** `@Deprecated(forRemoval = true)` on it. Deprecating and
   removing in the same release fails: the baseline carries no warning, so the deletion is
   indistinguishable from an undeclared removal.
2. The version increment is a **major**.
3. An entry in `revapi.differences` in the reactor root POM, with a justification naming the release
   that deprecated it:

```json
{"code": "java.method.removed",
 "old": "method void ru.ludwigandreas.audit.AuditSink::legacyRecord(…)",
 "justification": "Deprecated for removal in 1.4.0; removed in 2.0.0. Replacement: record(AuditEvent)."}
```

A removal nobody wrote a line for fails the build whatever the version says.

> **The rule that is written down and not checked:** a deprecated API should survive at least one
> *minor* release before removal. Nothing enforces it — the gate compares one build against one
> baseline, and two artifacts cannot say how many releases separate them. It is a comment beside the
> revapi configuration and an entry in `docs/harness-enforcement.md`, deliberately, rather than a
> rule that looks enforced and is not.

### 5.5 If the gate reports something that is not a break

It has happened, and the answer depends on which kind:

- **`java.missing.newClass`** — a type revapi could not load. Usually fixable at the cause
  (a `provided`-scope dependency it did not resolve). Fix it there. Ignoring a class revapi *could*
  have read turns an incorrect analysis into a quiet one.
- **`externalClassExposedInAPI` / `nonPublicPartOfAPI`** — already exempted globally. These are
  computed from the new archive alone and are not statements about two versions.

The test for any new exemption: **a code that compares two archives stays armed; a code computed from
one archive is not a compatibility statement.** Add it with a justification or do not add it.

---

## 6. Releasing

### 6.1 A minor or patch release

```bash
# 1. develop is green and IFT is happy.
# 2. Write the changelog entry — BOTH locales, same heading.
#    ## [1.3.0] in CHANGELOG.md and CHANGELOG.ru.md
# 3. Merge develop into master via PR.
# 4. Tag the merge commit.
git switch master && git pull
git tag -a v1.3.0 -m 'Release 1.3.0'
git push origin v1.3.0
# 5. Merge master back into develop, so the tag is in its history.
```

That is the whole procedure. The pipeline recognises the tag, drops the `-SNAPSHOT`, adds `-Prelease`
and deploys to the releases repository.

`-Prelease` does not set the version — it *enforces*: a concrete version, no SNAPSHOT dependencies,
and a changelog heading for that version **in both locales**, naming whichever is missing. Skipping
step 2 fails the build at `validate`, before anything is compiled.

### 6.2 A major release

Identical, with two additions:

- Somewhere in the history since the last release, a commit message carrying `+semver: major`, so the
  develop SNAPSHOT already read `2.0.0-SNAPSHOT` and the API gate was checking against that while you
  worked.
- Every removal carries its `revapi.differences` entry (section 5.4).

Tag `v2.0.0`. The tag is what buys the breaking change.

### 6.3 A hotfix

```bash
git switch master && git pull
git switch -c hotfix/short-description
# fix + test + OpenSpec change + changelog entry in both locales
# PR into master
git switch master && git pull
git tag -a v1.2.1 -m 'Hotfix 1.2.1'
git push origin v1.2.1
git switch develop && git merge master      # ALWAYS. Do not skip.
```

**The merge back is the step that gets forgotten**, and the symptom is the fix disappearing at the
next release because `develop` never had it.

> **Known gap, since IFT tracks `develop`:** a hotfix going straight to `master` **does not pass
> through IFT.** That is the price of the simple environment model. For anything larger than a
> one-line fix, either merge to `develop` first and let IFT see it, or deploy the hotfix branch to
> IFT by hand before tagging. Decide deliberately; do not discover it afterwards.

### 6.4 What a release publishes

Under `-Pci`, every jar parented by the reactor root publishes its **jar, flattened POM, `-sources`
jar and `-javadoc` jar**. A default `mvn clean install` builds none of the last two, so the local loop
stays fast. Services publish neither — they ship as images, pinned by name, version **and** `sha256`
digest.

### 6.5 Rolling back

There is no un-publish. A released version is immutable, which is the point. Roll forward: fix, and
cut the next patch. If a release is bad enough to pull, redeploy the previous image digest — it is in
the previous release's manifest, which is why every image reference carries one.

---

## 7. The gate, in one place

```bash
scripts/gate.sh --change <name> <module> [<module> …]   # runs everything below
scripts/gate.sh --list <module>                          # or just print it
```

By hand, in order:

```bash
scripts/check_image_pins.sh        # every image reference carries a digest
scripts/check_api_baseline.sh      # an unresolvable baseline is not an absent one
scripts/manifest.sh build          # only if a POM changed
mvn -q validate                    # Checkstyle + enforcer, every module
mvn -pl :<touched> -am verify
mvn -pl :<each in-repo dependent> -am verify
openspec validate <change>
```

`mvn test` alone runs **no** integration test. `mvn verify` does.

Local-loop escape hatches, never in a pipeline and never in a file:
`-Dcheckstyle.skip=true`, `-Djacoco.skip`, `-Denforcer.skip`, `-DskipTests`, `-Drevapi.skip=true`.

---

## 8. Where to look next

| Question | File |
|---|---|
| What must the code look like? | `CLAUDE.md`, then the module's own `README.md` |
| What does this module promise other modules? | `openspec/specs/<capability>/spec.md` |
| Which rules are actually enforced, and which are only written down? | `docs/harness-enforcement.md` |
| How do I find things in a 30-module reactor? | `docs/code-index.md`, `PROJECT_INDEX.md` |
| I am an agent, not a person | `docs/agent-operations.md`, `docs/agent-state.md` |
