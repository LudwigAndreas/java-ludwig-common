## 1. Pin what is unpinned

- [x] 1.1 Add a `maven-jar-plugin.version` property and a `pluginManagement` entry for
  `maven-jar-plugin` to the reactor root `pom.xml`, and delete the version-less configuration's
  reliance on Maven's default in `build/architecture-rules/pom.xml` (keep its `<archive>` block,
  which is load-bearing — the JSON report reads `Implementation-Version` from the manifest).
  Verify: `mvn -o validate 2>&1 | grep -c "maven-jar-plugin is missing"` prints `0`, and
  `mvn -pl :architecture-rules -am verify` passes.
- [x] 1.2 Add `requirePluginVersions` to the root POM's enforcer execution and to
  `ludwig-service-parent`'s existing `enforce-*` execution.
  Verify: `mvn -q validate` passes across the reactor. If the rule reports plugins inherited from
  Maven's super-POM, add `<unCheckedPluginList>` naming each with a comment saying why; if that
  proves unworkable, record the failure in `state.json` and implement the grep fallback named in
  design.md §Risks instead of weakening the rule.

## 2. The deprecation contract (source-text half)

- [x] 2.1 Add `MissingDeprecated` to `build/checkstyle-rules`' configuration, so a `@Deprecated`
  declaration without a Javadoc `@deprecated` tag fails.
  Verify: `mvn -pl :checkstyle-rules -am verify`, then `mvn -q validate` over the reactor — the four
  existing `@Deprecated` sites must either pass or be fixed in this task.
- [x] 2.2 Add a `RegexpSinglelineJava` check with id `DeprecationWithoutSince` requiring `since` and
  `forRemoval` members, with the comment beside it stating the single-line limit named in design.md
  §Decisions 5 and the `// SUPPRESS CHECKSTYLE ID DeprecationWithoutSince` form.
  Verify: a fixture under `build/checkstyle-rules`' own tests asserts the rule fires on bare
  `@Deprecated` and passes on `@Deprecated(since = "1.1.0", forRemoval = false)`; then
  `mvn -pl :checkstyle-rules -am verify`.
- [x] 2.3 Bring the reactor's existing `@Deprecated` sites up to the new rule (add `since`,
  `forRemoval` and the `@deprecated` tag naming the replacement).
  Verify: `mvn -q validate` passes with no suppression added.

## 3. Javadoc jars

- [x] 3.1 Pin `maven-javadoc-plugin` in the root POM's `pluginManagement` with
  `doclint=all,-missing`, `detectJavaApiLink=false` and `quiet=true`, and attach `jar` in the `ci`
  profile beside the existing `maven-source-plugin` execution.
  Verify: `mvn -Pci -DskipTests -pl :audit-core -am package` produces
  `sources/audit-core/target/audit-core-*-javadoc.jar`; `mvn -pl :audit-core -am package` (no
  profile) produces none.
- [x] 3.2 Confirm the doclint setting fails on malformed Javadoc and not on missing Javadoc, by
  introducing a `{@link NoSuchType}` locally, observing the failure, and reverting it.
  Verify: quote both outputs in `state.json` `decisions`; `mvn -q validate` still reports the
  missing-Javadoc warnings as warnings.

## 4. Changelogs

- [x] 4.1 Create `CHANGELOG.md` and `CHANGELOG.ru.md` at the repository root in Keep-a-Changelog
  form, with an `## [Unreleased]` section and a `## [1.1.0]` section describing this change and the
  baseline it establishes.
  Verify: both files carry identical heading sets —
  `diff <(grep -E '^## ' CHANGELOG.md) <(grep -E '^## ' CHANGELOG.ru.md)` is empty.
- [x] 4.2 Add the `evaluateBeanshell` enforcer rule to the root POM's `release` profile that fails
  when either changelog lacks a heading for `${revision}`, and a second, unconditional rule that
  fails when `.mvn/maven.config`'s `-Drevision` value is not a `-SNAPSHOT`.
  Verify: `mvn -q validate` passes; `mvn -q -Prelease -Drevision=9.9.9 validate` fails naming the
  missing changelog heading; `mvn -q -Prelease -Drevision=1.1.0 validate` passes the changelog rule.
- [x] 4.3 Add the changelog entry to `openspec/config.yaml`'s `archive` guidance, beside the existing
  both-locales README rule.
  Verify: `openspec validate add-api-compatibility-and-release-governance`.

## 5. The API gate, inert

- [x] 5.1 Add `revapi-maven-plugin` and its extensions to the root POM with versions as properties,
  bound to `verify`, `oldVersion` resolving the newest release from `ludwig.repo.releases`,
  `failBuildOnProblemsFound` **off**, and the semver `versionIncreaseAllows` configuration from
  design.md §Decisions 2. Verify the exact option names against the plugin version actually resolved
  (design.md §Open Questions) and record what you found in `state.json` `decisions`.
  Verify: `mvn clean install` passes and each library's build logs a revapi analysis; no service does.
- [x] 5.2 Write the missing-baseline and unresolvable-baseline behaviours required by
  `specs/api-evolution/spec.md`: absent is a logged non-comparison naming the module, unresolvable is
  a failure. If the plugin cannot distinguish them, add the wrapper check to `scripts/gate.sh` rather
  than relaxing the requirement.
  Verify: `mvn -pl :jira-client -am verify` on a clean local repository logs the "establishes the
  baseline" line; the same with an unreachable repository URL fails.
- [x] 5.3 Add the comment beside the revapi configuration recording that the one-minor removal window
  cannot be checked from a single baseline comparison, and the boundary sentence from design.md
  §Decisions 1 to the relevant `RuleGroup` javadoc in `architecture-rules` (ArchUnit must not grow an
  API-removal rule).
  Verify: `mvn -pl :architecture-rules -am verify`; `openspec validate <change>`.

## 6. GitVersion

- [ ] 6.1 Add `gitversion.yml` at the repository root: `mode: ContinuousDelivery`, the branch
  increments, and nothing that emits a pre-release label (design.md §Decisions 4).
  Verify: run the digest-pinned container against the checkout and confirm
  `/showvariable MajorMinorPatch` prints a bare `x.y.z`; quote it in `state.json`.
- [x] 6.2 Add the version stage to `Jenkinsfile`: a non-shallow checkout that fetches tags, the
  digest-pinned `gittools/gitversion` invocation, the tag/no-tag suffix decision, and
  `-Drevision=<computed>` on every subsequent `mvn` line. Keep every profile explicit — no implicit
  activation from the environment.
  Verify: `grep -n "revision" Jenkinsfile` shows it on each `mvn` invocation, and the image reference
  matches `\S+:\S+@sha256:[0-9a-f]{64}`.
- [x] 6.3 Add `scripts/check_image_pins.sh` (every `image:`-style reference in `Jenkinsfile`, YAML and
  Markdown carries a digest) and wire it into `scripts/gate.sh`.
  Verify: `scripts/check_image_pins.sh` exits 0 on the current tree and non-zero on a temporary
  digest-less copy; `scripts/gate.sh --list :architecture-rules` shows it.
- [x] 6.4 Demote `.mvn/maven.config` in the documentation: `CLAUDE.md`, `docs/agent-operations.md`
  §6, the root `README.md` release section, and tighten `.claude/settings.json`'s conditional deny on
  editing it to unconditional.
  Verify: `grep -rn "maven.config" CLAUDE.md docs README.md` shows no instruction to edit it for a
  release; the hook refuses a `Write` to `.mvn/maven.config` even in a session mentioning a release.

## 7. Specs, docs and the enforcement map

- [x] 7.1 Update `docs/harness-enforcement.md`: move the rows this change mechanises out of the
  backlog, and add the one-minor removal window as a new "cannot be mechanised" entry with its
  reason.
  Verify: `openspec validate add-api-compatibility-and-release-governance`.
- [x] 7.2 Update `build/architecture-rules/README.md` + `README.ru.md` (the revapi boundary) and the
  root `README.md` + release section (tag-driven release, the new artifact set).
  Verify: both locales of each README changed —
  `git diff --name-only | grep -E 'README(\.ru)?\.md'` lists them in pairs.

## 8. Land inert, then arm (operator steps marked)

- [x] 8.1 Full reactor build green with everything above in place and revapi still non-failing.
  Verify: `mvn clean install` and `python3 scripts/check_aggregate_report.py`.
- [ ] 8.2 **Operator step, not an agent step** (`-Pci` and `deploy` are denied to agents): tag
  `v1.1.0` on the merge commit and run `mvn -Prelease -Pci deploy` to publish the baseline.
  Verify: `ludwig.repo.releases` holds `1.1.0` jars, flattened POMs, `-sources` and `-javadoc` jars
  for all 25 library modules.
- [ ] 8.3 Acceptance: re-run the build against the published baseline with no source change and
  confirm **zero** revapi differences. Fix configuration artefacts here, not by relaxing severities.
  Verify: `mvn clean install`, with the revapi report quoted in `receipt.json`.
- [ ] 8.4 Arm the gate: set `failBuildOnProblemsFound` on.
  Verify: `mvn clean install` passes; a temporary removal of a public method from `audit-core` fails
  `mvn -pl :audit-core -am verify` naming the method and the required increment; revert it.

## 9. The verification gate, in full

- [x] 9.1 `scripts/manifest.sh build` (POMs changed), then confirm `scripts/manifest.sh stale` exits 0.
- [x] 9.2 `mvn -q validate` — Checkstyle across every module, including the two new rules.
- [x] 9.3 `mvn clean install` — the root POM and `ludwig-service-parent` both changed, so
  `scripts/gate.sh` substitutes the whole reactor for a narrow build; there is no narrower gate.
- [x] 9.4 `mvn -pl :architecture-rules -am verify` plus its six in-repo dependents —
  `:crud-service-example`, `:file-ingest-spring-boot-starter`, `:messaging-spring-boot-starter`,
  `:notification-service`, `:object-storage-spring-boot-starter`, `:user-settings-spring-boot-starter`
  — covered by 9.3 but named here because the dependents are the direction that catches a break.
- [x] 9.5 `python3 scripts/check_aggregate_report.py` and
  `openspec validate add-api-compatibility-and-release-governance`.
- [x] 9.6 Write `receipt.json` per `docs/agent-state.md` with the real gate output, the revapi option
  names actually verified, whether `requirePluginVersions` needed an `unCheckedPluginList`, and
  anything left unresolved.
