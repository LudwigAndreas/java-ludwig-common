## Why

This repository publishes a BOM, a service parent and 25 jars that other teams' services consume,
and **nothing checks what those jars promise between one release and the next.** A method can be
removed, a parameter type widened, an enum constant deleted or a `@Deprecated` API dropped, and the
build stays green; the consumer finds out at their own runtime with a `NoSuchMethodError`. There is
no changelog, so a consumer cannot tell what changed; no deprecation convention, so the four
`@Deprecated` sites in the reactor state neither *when* they were deprecated nor *what to use
instead*; and no Javadoc jar, so `-Pci` ships debuggable sources and no documentation. `@since`
appears in zero files, which is the same gap seen from the other side.

The version mechanism is the same gap one level up. `-Drevision=1.1.0-SNAPSHOT` in
`.mvn/maven.config` is a hand-edited literal, there are **zero git tags**, and the release procedure
is a human remembering to pass `-Drevision=1.2.0`. Forgetting is caught (`requireReleaseVersion`),
but *choosing the wrong number* — a patch bump over a breaking change — is not, and that is the
mistake an API-compatibility gate exists to catch. The two halves only work together: a compatibility
gate needs to know which version bump is being attempted, and a computed version needs something to
tell it that a bump is too small.

One loose end belongs with them: `build/architecture-rules/pom.xml:124` configures
`maven-jar-plugin` with no version, which Maven reports as a malformed model whose "problems threaten
the stability of your build". It is the only unpinned plugin in a repository whose entire thesis is
pinning, and it is fixed here because the same enforcer execution that fixes it forbids the next one.

## What Changes

- **Versions are computed by GitVersion, not written by hand.** A digest-pinned
  `gittools/gitversion` container computes the SemVer from the git history; Jenkins passes it in as
  `-Drevision=<computed>`. `master` yields a release version, every other branch yields
  `<MajorMinorPatch>-SNAPSHOT`, so Maven's release/snapshot repository split keeps working.
  `.mvn/maven.config` keeps a `-Drevision` value as the **local, tag-less fallback only** — a
  command-line user property beats it, which is exactly the precedence this needs.
- **Releases are cut by tagging**, not by editing `.mvn/maven.config`. `v1.2.0` on `master` is the
  release; the file stops being release-critical, and CLAUDE.md's "do not edit `maven.config` unless
  you are doing a release" becomes "do not edit it at all".
- **A revapi gate on every published library.** Bound to `verify` in the reactor root, so the 25 jar
  modules parented by `common` get it and the two services — which inherit
  `ludwig-service-parent` — never do. The baseline is the previous release resolved from
  `ludwig.repo.releases`; the severity of the difference is checked against the version bump
  GitVersion computed, so a binary-breaking change fails unless the major moved.
- **The API baseline is seeded from current HEAD**: `v1.1.0` is tagged and published as the
  deliberate compatibility floor, and every build after it is gated.
- **A deprecation policy with teeth.** `@Deprecated` SHALL carry `since` and `forRemoval`, and the
  Javadoc `@deprecated` tag SHALL name the replacement. Checkstyle owns the annotation-and-tag half
  (source text); revapi owns the removal half (bytecode against a baseline).
- **`CHANGELOG.md` and `CHANGELOG.ru.md`** in Keep-a-Changelog form, one pair for the whole reactor
  because all 30 modules share one `${revision}` and release together. `-Prelease` fails when
  neither file carries a heading for the version being released.
- **Javadoc jars attached under `-Pci`**, beside the sources jars, with doclint failing on malformed
  Javadoc (broken links, bad tags) and **not** on missing Javadoc — the 288 missing-Javadoc warnings
  stay the existing warning tier and are not smuggled into this change as a blocker.
- **`maven-jar-plugin` pinned**, and `requirePluginVersions` added to the enforcer so the next
  unpinned plugin fails the build instead of printing a warning nobody reads.

## Capabilities

### New Capabilities
- `api-evolution`: what this platform's published API is allowed to do between releases — the revapi
  baseline and gate, which differences are breaking, how a breaking change is permitted (a major
  bump), and the deprecation contract (`since`, `forRemoval`, a named replacement, and the removal
  window) that precedes every removal.
- `release-versioning`: where the version comes from — GitVersion over git history, the
  branch/tag-to-version mapping, snapshot versus release and the repository each goes to, the
  `.mvn/maven.config` fallback and its precedence, and the changelog entry a release requires.
- `published-artifact-set`: what every published module ships — the jar, the flattened POM, and
  under `-Pci` the sources and Javadoc jars — and the rule that every plugin taking part carries a
  version.

### Modified Capabilities
- `pom-topology`: the "version lives in exactly one place" requirement changes meaning. `${revision}`
  in every POM stays exactly as it is; what changes is *what sets it* — GitVersion in CI, with
  `.mvn/maven.config` demoted to a fallback for a checkout with no tags. The release scenario changes
  with it: a release is a tag plus `-Prelease -Pci deploy`, and `-Prelease` keeps enforcing rather
  than setting. Its plugin-version scenario is untouched — the new `requirePluginVersions` check
  belongs to `published-artifact-set`, so that one rule has one owner.

## Impact

**POM tier of every module this change edits** (from `scripts/manifest.sh module <path>`):

| Module | Tier | In-repo dependents | Why it is edited |
|---|---|---|---|
| `pom.xml`, artifact `common` | reactor root and parent of the **library** modules | every library and starter | revapi, `maven-javadoc-plugin`, `maven-jar-plugin` pin, `requirePluginVersions`, the `-Prelease` changelog rule, `-Pci` javadoc attachment |
| `ludwig-service-parent` | parent (its own parent is `spring-boot-starter-parent`) | 0 — services inherit it, the index records no dependency edge | `requirePluginVersions` only, for symmetry. It deliberately gets **no** revapi and **no** javadoc jar: a service is not consumed as a library |
| `architecture-rules` | library (parented by `common`, `packaging=jar`, role `rules`) | 6 | the unpinned `maven-jar-plugin`, which `requirePluginVersions` would otherwise fail on |

`ludwig-bom` is **not** touched: revapi's plugin and extension versions are plugin configuration,
which the `pom-topology` capability puts in the root POM, and the BOM holds third-party
*dependency* versions only.

The 25 jar modules parented by `common` — `odata-filter-spring-boot-starter`, `audit-core`,
`db-core`, `audit-spring-boot-starter`, `job-core`, `idempotency-spring-boot-starter`,
`cache-spring-boot-starter`, `messaging-spring-boot-starter`, `export-spring-boot-starter`,
`object-storage-spring-boot-starter`, `file-ingest-spring-boot-starter`, `outbox-spring-boot-starter`,
`reconciliation-spring-boot-starter`, `security-spring-boot-starter`,
`identity-projection-spring-boot-starter`, `hot-reload-spring-boot-starter`,
`web-core-spring-boot-starter`, `observability-spring-boot-starter`,
`rest-client-spring-boot-starter`, `user-settings-spring-boot-starter`, `architecture-rules`,
`checkstyle-rules`, `test-support`, `test-support-security`, `jira-client` (all 25 by name above; plus
`jacoco-aggregate` which is `packaging=pom` and has no API) — all gain the gate by inheritance with
no POM edit of their own. `crud-service-example` and `notification-service` gain nothing.

**Shared contracts in `openspec/specs/`:** `pom-topology` is modified (above). `container-image-pinning`
is **touched but not modified** — the GitVersion image is a new image reference and the existing
requirement already covers "every container image reference anywhere in this repository", so it is an
instance of that rule, not a change to it. `enforcement-triad` is **not** modified: the deprecation
annotation rule is a source-text fact and goes to `checkstyle-rules`, the compatibility rule is a
bytecode fact and goes to revapi — which is a fourth tool, and the design must say why it does not
break the triad. No other capability changes.

**Outside the reactor:** `Jenkinsfile` (a version stage, and a non-shallow checkout, which GitVersion
requires), `CLAUDE.md` and `docs/agent-operations.md` (the `maven.config` restriction), `README.md`
(the release procedure), `docs/harness-enforcement.md` (three rows move from backlog to enforced),
`.claude/settings.json` (the deny on editing `maven.config` can be tightened to unconditional), and
`openspec/config.yaml` (the archive guidance gains the changelog entry).

**The verification gate** for this change is `mvn clean install`, because the root POM and
`ludwig-service-parent` both change and `scripts/gate.sh` substitutes the full build for a narrow one
in exactly that case, plus `openspec validate add-api-compatibility-and-release-governance`.

## Non-goals

- **No SBOM, vulnerability scan or artifact signing.** They belong to the supply-chain change and
  share none of this one's mechanism.
- **No clearing of the 288 missing-Javadoc warnings.** Doclint is configured to fail on malformed
  Javadoc only. Turning the warning tier into an error is a separate, larger change and would make
  this one unmergeable.
- **No Spring Boot or Java upgrade**, even though a major version bump is the natural moment for one.
- **No change to the JaCoCo coverage floors** or to which CI stage runs integration tests. Those are
  the coverage-integrity change; this one must not be the vehicle for them.
- **No per-module changelog and no per-module version.** One `${revision}` for the reactor is an
  existing decision this change relies on rather than revisits.
- **No release automation beyond the tag.** Nothing here creates a tag, writes release notes or
  pushes to a registry on its own; a human tags, and the pipeline reacts.
- **No `japicmp`.** revapi is chosen and the design states why; two compatibility checkers would be
  the overlap the enforcement triad forbids.
