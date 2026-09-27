# What is actually enforced, and what is only written down

The standing principle in this repository is that **every rule is encoded twice** — as a guide an
agent reads, and as a check that fails when it is broken. A rule with a guide and no check is a rule
that will be broken silently.

This file is the honest accounting of which is which. Every restriction in
`docs/agent-operations.md` §6 appears below, mapped either to the mechanism that enforces it or to a
statement that none does and why. **The second list is the backlog**, and it is the more useful half.

## Enforced

| Restriction | Mechanism | Verified |
|---|---|---|
| No `git push --force` / `-f` | `permissions.deny` in `.claude/settings.json` | rule present |
| No `mvn deploy`, `jib:build`, `-Pci` | `permissions.deny` (four patterns) | rule present |
| No `-Dcheckstyle.skip` / `-Djacoco.skip` / `-Denforcer.skip` **on a command line** | `permissions.deny` | rule present |
| No edit under `**/target/**` | `permissions.deny` **and** `.claude/hooks/guard-edit.sh` | **yes** — a `Write` to `job-core/target/` was refused |
| No edit under `**/generated-sources/**` | `permissions.deny` and the same hook | hook pipe-tested |
| No edit of `.flattened-pom.xml` | `permissions.deny` and the same hook | hook pipe-tested |
| No edit under `openspec/changes/archive/**` | `permissions.deny` and the same hook | **yes** — a `Write` there was refused |
| No edit of `.mvn/maven.config` outside a release | `.claude/hooks/guard-edit.sh`, conditional on the session transcript mentioning a release | hook pipe-tested both ways |
| `clear_settings` on the code index | `permissions.deny` | rule present |
| A stale manifest goes unnoticed | `scripts/manifest.sh stale` (content SHA over the POM set), the `SessionStart` hook, the `PostToolUse` hook on `pom.xml`, and `scripts/gate.sh` regenerating it | all pipe-tested |
| The gate skips a module's in-repo dependents | `scripts/gate.sh` reads them from the manifest rather than accepting a list | **yes** — ran 10 commands for `job-core`, all passing |
| A BOM or parent change gets a narrow gate | `scripts/gate.sh` substitutes `mvn clean install` and says so | **yes** |
| A change finishes with no verification | `Stop` hook prints the gate for the modules touched | pipe-tested |
| One audit sink, no second audit SPI, no `*.audit` logger | ArchUnit `RuleGroup.AUDIT` | pre-existing |
| No second redaction mask | Checkstyle `SecondRedactionMask` | pre-existing |
| No second operation status vocabulary | ArchUnit `RuleGroup.OPERATIONS` | pre-existing |
| No module-local Caffeine builder or second cache SPI | ArchUnit `RuleGroup.CACHING` | pre-existing |
| `cache-spring-boot-starter` has no in-repo dependencies | `ModuleIndependenceTest` | pre-existing |
| SQL confined to two packages | a `SqlConfinementTest` in each of those two modules | pre-existing, **and incomplete — see below** |
| Every test image pinned by digest | `ImagePinningTest` | pre-existing |
| No hard-coded user-facing text | Checkstyle `NonAsciiSourceText` | pre-existing |

## Not enforced — the backlog

Each of these is a real rule that an agent can break with the build staying green. They are listed
worst-first by how quietly they fail.

**1. Weakening a Checkstyle or ArchUnit rule to make code pass.** Nothing detects it. Relaxing a
rule in `checkstyle.xml`, deleting an ArchUnit rule from a `RuleGroup`, or adding a `@Disabled` to a
failing test all look like ordinary edits, and afterwards the build is green *because* the check is
gone. This is the single most dangerous gap, because it converts every other enforced rule into an
unenforced one.
*What it would take:* a test asserting the count and identity of enabled rules against a golden
list, so removing one fails until the golden list is edited in the same commit — which makes the
removal visible in review instead of invisible. Cheap to write, and it belongs in `checkstyle-rules`
and `architecture-rules` respectively.

**2. Widening an existing suppression to cover new code.** Same class of failure: the suppression
comment already exists and already names its rule and reason, so the diff looks compliant.
*What it would take:* a check that a suppression's reason mentions the code it covers, which is not
mechanisable; or, more realistically, treating any change to a suppression as review-required.
`convention-auditor` is asked to look for it, which is a guide, not a check.

**3. `-DskipTests` and the other escape hatches committed into a POM, a script or the Jenkinsfile.**
The permission deny only covers a command line. A POM that sets `<skipTests>true</skipTests>`, or a
Jenkinsfile stage that passes `-Dcheckstyle.skip`, passes every check here.
*What it would take:* a grep-level check over `**/pom.xml`, `Jenkinsfile` and `scripts/**` in the
gate or in CI. Straightforward; not done.

**4. Native SQL outside the two fenced packages.** `CLAUDE.md` says two carve-outs exist. In fact
native SQL appears in nine main-source files across six modules, and only the two named packages
have a `SqlConfinementTest`. The other seven sites all carry justifying javadoc, so they meet the
*spirit* of the rule — but nothing would stop an eighth that does not.
*What it would take:* either a `SqlConfinementTest` in each of `export-spring-boot-starter`,
`job-core`, `notification-service` and `reconciliation-spring-boot-starter` naming their legitimate
package, or one repository-wide ArchUnit rule with a declared exemption list. See the `data-access`
capability spec, which records the discrepancy rather than hiding it.

**5. A cache declaring the wrong `CachePurpose`.** A security-relevant cache declared
`performance` gets minutes of TTL, stale reads and the load lease, and lengthens a revocation
window silently. **This one genuinely cannot be mechanised**: whether a cache's contents decide
what a caller is allowed to do is a fact about meaning, not about structure, and no bytecode or
source-text analysis can see it. It is recorded in the `cache-purpose` spec as the one mistake the
module cannot detect for itself, which is the correct treatment of an unmechanisable rule.

**6. Credentials in a POM, YAML or source file.** Deliberately not enforced here: SonarQube and
SCM secret scanning own bugs and security in this repository's division of labour, and adding a
third opinion would be exactly the triad overlap the rules forbid. Recorded so that its absence
reads as a decision rather than an oversight.

**7. "Never commit or push unless asked."** Not mechanised, and should not be: a deny on `git
commit` would also block the commits somebody explicitly asks for, and a harness that blocks the
legitimate case gets switched off. Guide only.

**8. "Never rewrite published history."** Only partly enforced. `--force` and `-f` are denied, but
`git rebase` followed by an ordinary push to a rewritten branch is not.
*What it would take:* a `PreToolUse` hook on `Bash` inspecting `git push` against the branch's
upstream. Not done; the deny covers the common case.

**9. A second code index, or a hand-edited manifest.** Guide only, in `docs/code-index.md`,
`CLAUDE.md` and `docs/agent-operations.md` §6. Nothing stops somebody adding `universal-ctags` back,
or editing `PROJECT_INDEX.md` by hand — and a hand-edited manifest is the worse of the two, because
`scripts/manifest.sh stale` compares POM content and would still report "current".
*What it would take:* a generated-file marker checked by the gate, and a grep for the retired tools
in `scripts/**`. Both easy; neither done.

**10. An agent's own scope limits.** `spec-author` must not edit source and `implementer` must not
edit specs, and **neither restriction is mechanical.** A tool allowlist grants `Write` and `Edit`
wholesale; it cannot scope them to a path. So the separation that makes the spec an independent
check — the property the brief calls more important than the agent roster — rests on the agent
prompt alone. The three read-only agents (`verifier`, `convention-auditor`, `manifest-keeper`) *are*
mechanically confined, because they were given no write tool at all.
*What it would take:* path-scoped `Edit`/`Write` permissions per agent, which the settings schema
supports for the session (`Edit(path)`) but which agent definitions express only as a tool list. A
`PreToolUse` hook keyed on the active agent would close it.

## The shape of the gap

Eight of the ten are the same failure: **the harness is good at stopping an agent from touching the
wrong file, and poor at stopping it from changing the rules.** Path rules are positional and a deny
list expresses them exactly. "Do not make the check weaker" is a statement about intent, and the
only mechanical form it has is a golden list that makes weakening visible rather than impossible.
That is the next thing worth building.
