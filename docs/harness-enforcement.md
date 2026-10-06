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
| No edit under `**/target/**` | `permissions.deny` **and** `.claude/hooks/guard-edit.sh` | **yes** — a `Write` to `sources/job-core/target/` was refused |
| No edit under `**/generated-sources/**` | `permissions.deny` and the same hook | hook pipe-tested |
| No edit of `.flattened-pom.xml` | `permissions.deny` and the same hook | hook pipe-tested |
| No edit under `openspec/changes/archive/**` | `permissions.deny` and the same hook | **yes** — a `Write` there was refused |
| No edit of `.mvn/maven.config`, ever | `permissions.deny` (four patterns) **and** `.claude/hooks/guard-edit.sh`, now unconditional — the transcript grep is gone, because a release is a tag and a deny that unlocks when the transcript says a word is not a deny | hook pipe-tested |
| `clear_settings` on the code index | `permissions.deny` | rule present |
| A stale manifest goes unnoticed | `scripts/manifest.sh stale` (content SHA over the POM set), the `SessionStart` hook, the `PostToolUse` hook on `pom.xml`, and `scripts/gate.sh` regenerating it | all pipe-tested |
| The gate skips a module's in-repo dependents | `scripts/gate.sh` reads them from the manifest rather than accepting a list | **yes** — ran 10 commands for `job-core`, all passing |
| A BOM or parent change gets a narrow gate | `scripts/gate.sh` substitutes `mvn clean install` and says so | **yes** |
| A change finishes with no verification | `Stop` hook prints the gate for the modules touched | pipe-tested |
| One audit sink, no second audit SPI, no `*.audit` logger | ArchUnit `RuleGroup.AUDIT` | pre-existing |
| No second redaction mask | Checkstyle `SecondRedactionMask` | pre-existing |
| No second operation status vocabulary | ArchUnit `RuleGroup.OPERATIONS` | pre-existing |
| No module-local Caffeine builder or second cache SPI | ArchUnit `RuleGroup.CACHING` | **was inert until this change** — it was written as `noClasses().should(notDependOnClassesThat(..))`, and `noClasses()` wraps the condition in ArchUnit's `never()`, which inverts each event; a helper that reports only violations therefore produces zero findings while the rule reports as passed. Measured against a fixture (0 violations over 5 classes that provably had the dependency), switched to `classes().should(..)`, and the same defect fixed in `KafkaRules.noPrivateDeadLetterRecoverer` |
| Nothing presents a time or a number from a JVM default (`Locale.getDefault`, `TimeZone.getDefault`, `ZoneId.systemDefault`, `Clock.systemDefaultZone`) | ArchUnit `RuleGroup.PRESENTATION` / `noAmbientDefaultLocaleOrZone` | **yes** — `PresentationRulesTest` asserts it names the offending fixture class, not merely that the rule ran |
| No second type holding the caller's locale-and-zone pair | ArchUnit `RuleGroup.PRESENTATION` / `noSecondCallerPreferenceType` | **yes** — the same test asserts it fires on the restated pair and stays silent on the per-subject shape and on a zone with no locale, in one run |
| `UserPreferenceFormatter` declares two methods for one source/target pair | `UserPreferenceFormatterTest`, reflectively over its own surface | **yes** — ArchUnit cannot see that two methods are MapStruct-ambiguous and Checkstyle sees text rather than resolved types, so the check lives in the module that owns the class |
| `cache-spring-boot-starter` has no in-repo dependencies | `ModuleIndependenceTest` | pre-existing |
| SQL confined to two packages | a `SqlConfinementTest` in each of those two modules | pre-existing, **and incomplete — see below** |
| Every test image pinned by digest | `ImagePinningTest` | pre-existing |
| No hard-coded user-facing text | Checkstyle `NonAsciiSourceText` | pre-existing |
| A published library's API breaks below a major increment | `revapi-maven-plugin` + the `revapi.semver.ignore` extension, at `verify`, for every module parented by the reactor root | armed (`failBuildOnProblemsFound` is `true`) but **not yet protecting anything**: with no `1.1.0` in the releases repository every module is compared against an *empty archive*, so a removal has nothing to be missing from. Arming it early did flush out three configuration artefacts that the acceptance step existed to find — see below. Real protection begins with the first build after the baseline is published |
| An element is removed with no justified exemption | a `revapi.differences` entry naming the release that deprecated it, absent ⇒ failure | same — armed with the gate |
| `@Deprecated` with no `since` / `forRemoval` | Checkstyle `DeprecationWithoutSince` | **yes** — it failed the build on its own test's constant before that constant was rewritten, and `DeprecationContractRulesTest` asserts it fires and does not over-fire |
| `@Deprecated` with no Javadoc `@deprecated` tag | Checkstyle `MissingDeprecated` | **yes** — same test |
| A plugin configured with no version | `maven-enforcer-plugin` `requirePluginVersions` in the reactor root **and** in `ludwig-service-parent`, with `banLatest`/`banRelease`/`banSnapshots`/`banTimestamps` | **yes** — it failed on five plugins Maven and Boot bound from their own super-POMs; all five are now pinned and the exclusion list is empty |
| A release with no changelog entry | two `evaluateBeanshell` rules in the `-Prelease` profile, one per locale | **yes** — `mvn -q -Prelease -Drevision=9.9.9 validate` fails naming both `CHANGELOG.md` and `CHANGELOG.ru.md` |
| `.mvn/maven.config` holding a non-SNAPSHOT | an unconditional `evaluateBeanshell` rule reading the file's text | **yes** — passes today, and the condition is on the file rather than on `${revision}`, which the command line overrides |
| Malformed Javadoc in a published library | `maven-javadoc-plugin` `-Xdoclint:all,-missing` under `-Pci` | **yes** — it found 20 real defects across 11 modules on first run, all fixed |
| A container image referenced by tag alone, anywhere | `scripts/check_image_pins.sh`, run first by `scripts/gate.sh` | **yes** — exits 0 on the tree, exits 1 naming file and line when a digest is removed |
| An API baseline that is unresolvable rather than absent | `scripts/check_api_baseline.sh`, run by `scripts/gate.sh` | **yes** — both branches exercised, including with a temporary tag and an unreachable repository |
| No second upload path - `MultipartFile` outside `file-action-spring-boot-starter` | ArchUnit `RuleGroup.UPLOADS` / `noSecondUploadPath` | **yes** - `UploadRulesTest` asserts it names the offending fixture class and stays silent on the compliant package, in one run, and the full reactor stayed green when the rule was added |
| A DOM workbook read (`XSSFWorkbook`, `HSSFWorkbook`, `WorkbookFactory`) in the file-action module | `PoiConfinementTest`, module-local | **yes** - verified red by temporarily adding a class calling `new XSSFWorkbook(in)`; 3 of 7 assertions failed naming the rule, and the probe was then deleted. Module-local rather than a platform `RuleGroup` because `export` legitimately DOM-reads an administrator-supplied template, so a platform-wide ban would be red on a module that is correct |
| A user's file materialised into the heap (`readAllBytes`, `Files.readAllLines`, `MultipartFile.getBytes`, `IOUtils`) | `NoMaterialisationTest`, module-local | **yes** - verified red in the same run as above |
| A third SQL carve-out appearing in `file-action-spring-boot-starter` | `NoSqlStringsTest`, module-local | **yes** - and its first version over-fired: `[^"]*` either side of the keyword spans newlines, so it matched the ordinary code between two unrelated string literals and failed on an entity's column declarations. Bounded to `[^"\n]*`; a rule that cries wolf is one that gets deleted |
| The streaming XLSX reader stops bounding the heap | `LargeWorkbookHeapIT` (deterministic, every build) plus `LargeWorkbookHeapMeasurementIT` (`@Tag("measurement")`, excluded by the overridable `file-action.test.excluded.groups`) | **yes** - the measurement carries a case that performs a DOM read of the same file and asserts it retains at least five times more, so the budget is proven capable of failing. Tagged out of the default build because the ratio passed alone and failed in the full suite: a heap measurement in a JVM shared with other classes and instrumented by JaCoCo is not a reliable assertion, and a flaky guarantee is worse than an honest tag |
| A file action with no declared `commit-policy`, a row-level policy on a `DocumentHandler`, an `INLINE` action above the inline ceiling, `scanning.mode: required` with no scanner, an authority with no security starter, or a problem code missing from a locale | `FileActionConfigurationValidator`, at startup | **yes** - `FileActionConfigurationValidatorTest` has one case per refusal, 16 in total, and `FileActionAutoConfigurationTest` asserts four of them fail a real `ApplicationContext`. Startup is the third enforcement point after ArchUnit and Checkstyle, and for a configuration fact it is the only one available |

### What the compatibility gate deliberately does not report

Arming `failBuildOnProblemsFound` before the baseline existed turned the first three reactor builds
red, none of them for a compatibility reason. Each one is now answered in the reactor root POM, and
the answers are different on purpose:

| Reported | Answer | Why that answer |
|---|---|---|
| `java.missing.newClass` for `jakarta.servlet.*` | **fixed at the cause** — `resolveTransitiveProvidedDependencies` | The classes were resolvable, just not resolved. revapi says outright that the analysis "may be incorrect" without them; ignoring a class it *could* have loaded turns an incorrect analysis into a quiet one |
| `java.missing.newClass` for SAML2 / LDAP / OAuth2-client types | exempted, with justification | `HttpSecurity` declares `saml2Login()`, `ldapAuthentication()` and `oauth2Client()`; their types live in jars this platform does not depend on and must not start depending on to satisfy a checker. Absent from the old archive and the new one alike, so never a difference between two releases |
| `java.class.externalClassExposedInAPI`, `java.class.nonPublicPartOfAPI` | exempted, with justification | revapi's API-*design* advisory family. Both are computed from the new archive alone, fire identically on a build with no source change, and would fire on a first release when there is nothing to be compatible with. Twenty-five Spring Boot starters cannot stop exposing Spring types |

The distinction that matters, and the one to apply to the next entry anybody is tempted to add:
**a code that compares two archives stays armed; a code computed from one archive is not a
compatibility statement.** Every removal and signature-change code — `java.method.removed`,
`java.method.parameterTypeChanged`, `java.method.addedToInterface`, `java.field.removed`,
`java.class.removed`, `java.class.kindChanged` — is in the first group and is armed.

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

**5. A deprecated API surviving one minor release before it is removed.** The `api-evolution`
capability requires it and **it genuinely cannot be mechanised.** revapi compares one build against
one baseline, and two artifacts cannot say how many releases separate them: the baseline is
whatever `RELEASE` resolves to today, not the sequence of everything ever published. Nothing in the
build has the release history, and giving it one would mean a second source of truth about what was
released, alongside the tags. It is recorded as a comment beside the revapi configuration in the
reactor root POM, which is the correct treatment of an unmechanisable rule. What *is* checked is the
adjacent, stronger thing: a removal must carry a `revapi.differences` entry whose justification
names the release that deprecated it, so a removal nobody wrote a line for fails whatever the
increment.

**6. A version increment that is larger than the change needs.** The compatibility gate refuses an
increment that is too SMALL and can never refuse one that is too large. A team that bumps the major
every release makes the gate vacuous and no tool can see it — "this did not need to be a major" is
a judgement about the change, not a property of the bytecode. Only the changelog catches it, and
only a reader of the changelog. Stated in the `api-evolution` spec.

**7. Whether a changelog entry describes the release accurately.** `-Prelease` checks that both
locales carry a heading for the version, which is the failure that actually happens. Whether the
text underneath it is true is not mechanisable, and the guidance lives in `openspec/config.yaml`'s
archive step beside the both-locales README rule.

**8. A cache declaring the wrong `CachePurpose`.** A security-relevant cache declared
`performance` gets minutes of TTL, stale reads and the load lease, and lengthens a revocation
window silently. **This one genuinely cannot be mechanised**: whether a cache's contents decide
what a caller is allowed to do is a fact about meaning, not about structure, and no bytecode or
source-text analysis can see it. It is recorded in the `cache-purpose` spec as the one mistake the
module cannot detect for itself, which is the correct treatment of an unmechanisable rule.

**9. Credentials in a POM, YAML or source file.** Deliberately not enforced here: SonarQube and
SCM secret scanning own bugs and security in this repository's division of labour, and adding a
third opinion would be exactly the triad overlap the rules forbid. Recorded so that its absence
reads as a decision rather than an oversight.

**10. "Never commit or push unless asked."** Not mechanised, and should not be: a deny on `git
commit` would also block the commits somebody explicitly asks for, and a harness that blocks the
legitimate case gets switched off. Guide only.

**11. "Never rewrite published history."** Only partly enforced. `--force` and `-f` are denied, but
`git rebase` followed by an ordinary push to a rewritten branch is not.
*What it would take:* a `PreToolUse` hook on `Bash` inspecting `git push` against the branch's
upstream. Not done; the deny covers the common case.

**12. A second code index, or a hand-edited manifest.** Guide only, in `docs/code-index.md`,
`CLAUDE.md` and `docs/agent-operations.md` §6. Nothing stops somebody adding `universal-ctags` back,
or editing `PROJECT_INDEX.md` by hand — and a hand-edited manifest is the worse of the two, because
`scripts/manifest.sh stale` compares POM content and would still report "current".
*What it would take:* a generated-file marker checked by the gate, and a grep for the retired tools
in `scripts/**`. Both easy; neither done.

**13. An agent's own scope limits.** `spec-author` must not edit source and `implementer` must not
edit specs, and **neither restriction is mechanical.** A tool allowlist grants `Write` and `Edit`
wholesale; it cannot scope them to a path. So the separation that makes the spec an independent
check — the property the brief calls more important than the agent roster — rests on the agent
prompt alone. The three read-only agents (`verifier`, `convention-auditor`, `manifest-keeper`) *are*
mechanically confined, because they were given no write tool at all.
*What it would take:* path-scoped `Edit`/`Write` permissions per agent, which the settings schema
supports for the session (`Edit(path)`) but which agent definitions express only as a tool list. A
`PreToolUse` hook keyed on the active agent would close it.

**A mapper that renders an instant in a fixed zone instead of the caller's.** `RuleGroup.PRESENTATION`
forbids the JVM defaults, and a mapper writing `instant.atOffset(ZoneOffset.UTC)` calls no forbidden
method and is still wrong. ArchUnit *could* forbid `ZoneOffset.UTC` outright and must not: it is
correct in a persistence mapping, a test fixture, an audit record and a Kafka envelope, which together
are the large majority of its uses, so the rule would be wrong more often than right.
*What it would take:* nothing mechanical that is worth having. It is recorded as a scenario in the
`user-preference-context` capability so a review has something to point at rather than a recollection.

**A service that wanted stored preferences and forgot to declare the settings.**
`WellKnownSettings` registers nothing automatically, on purpose, so a service that never contributed
`LOCALE` and `TIMEZONE` as a `SettingDefinitionSource` behaves exactly like one that never wanted
them: headers and configuration, no error, no warning, and a user whose saved timezone is silently
ignored. The difference is an *absent bean*, and an absent bean is indistinguishable from a deliberate
choice at build time.
*Mitigated, not solved:* `StoredUserPreferenceSource` is registered anyway and abstains out loud -
`sourceName()` names which dimensions it can answer and the startup line prints it - so the gap is
visible in a log rather than only in a surprised user.

**Whether the configured `default-zone` is the right zone for the deployment.** An operator who set
`UTC` because the business runs on UTC and one who set it because it was in the example produce
identical configuration.
*What it would take:* nothing. It is a statement about the business, and no artifact a build can read
contains it.

**15. A `RowHandler` that is not idempotent per row.** A `DEFERRED` submission is claimed under a
lease. If the pod dies after the transaction carrying rows 501-1000 committed but before the
submission's progress was recorded, another instance resumes and re-applies those rows - so a handler
that inserts unconditionally creates five hundred duplicate orders, once, on the day a node is
drained. Nothing in bytecode can see whether a write is conditional on a natural key.
*What it would take:* nothing mechanical. It is stated on `RowHandler.apply`, at the seam the author
reads, and the module's README repeats it. An ArchUnit rule could at best forbid a bare `save` call
in a handler, which would be wrong far more often than right.

**16. A `DocumentHandler` that calls a partner service inside the apply transaction.** One
transaction covers every row, so a partner call inside it holds a database connection for the
partner's latency and exhausts the pool when the partner is slow - and if the transaction then rolls
back, the partner has still been called. A bytecode rule cannot tell a partner call from any other
method invocation.
*What it would take:* nothing mechanical either. Stated on `DocumentHandler.apply`, with the
`outbox-spring-boot-starter` alternative named there. A rule forbidding `rest-client` types inside a
handler would be close, but a partner reached through a service bean of the author's own is
indistinguishable from any other collaborator.

**17. Whether a declared `commit-policy` is the *right* one for the domain.** The validator forces
the declaration and refuses an action without one, which is the enforceable half. Whether
`PER_ROW` is correct for an accounting import, or `ALL_OR_NOTHING` for a contact list, is a domain
judgement - and both wrong answers present as applied business data rather than as a failure.
*What it would take:* nothing. This is the `CachePurpose` situation exactly, and the module says so
in the same words: it is the only mistake the module cannot detect for you.

**18. Whether a deployment's `FileScanner` actually scans.** `scanning.mode` defaults to `required`
and the application does not start without a bean, so a deployment cannot end up with no scanner by
accident. A bean returning `ScanOutcome.safe(...)` unconditionally satisfies every rule here, and
only a person reviewing it will notice.
*What it would take:* nothing mechanical within this repository. An integration test in the
consuming service that submits a known test signature - EICAR - and asserts the submission is
`REJECTED` would catch it, and that belongs in the deployment rather than in the module.

**19. A `DocumentHandler` action configured with a non-zero `reject-threshold`.** A row that fails to
bind is never written to the bound-row artifact, and the threshold - default `0.1` - decides whether
such rows refuse the submission at all. So a four-hundred-line order with thirty unbindable lines is
`0.075`, under the threshold, and the handler applies one order built from the three hundred and
seventy survivors with no way to know the thirty existed. That is the "we created the first 38 lines
of your order" outcome the handler split exists to prevent, arriving through the one property that
is orthogonal to the commit policy.
*What it would take:* a rule in `FileActionConfigurationValidator`, beside the existing refusal of a
row-level commit policy on a `DocumentHandler` - the resolved handler and the resolved threshold are
both already in hand at that point, so it is a two-line check and a message. This is the one item in
this list that is cheaply enforceable and not yet enforced; it is written down on
`DocumentHandler.apply` and in the module's README in the meantime.

**20. A per-row validation rule implemented inside a handler rather than at bind time.** `ApplyPass`
strips the `RowAddress` before calling a `DocumentHandler`, and one invocation returns one
`RowOutcome`, so a rule evaluated per row in there reports its first failure with no location - and
the symptom is not a failure but a worse error report, which nobody files a bug about. The same
mistake in a `ConstraintValidator` is a stateful singleton: a validator accumulating seen keys in a
field to spot duplicates leaks across submissions and corrupts concurrent ones, under load, never in
a test.
*What it would take:* for the stateful validator, an ArchUnit rule forbidding non-final instance
fields on `ConstraintValidator` implementations would catch the common form and is worth writing. For
the misplaced rule itself, nothing mechanical: a predicate over the row inside `apply` is
indistinguishable from the cross-row fold that belongs there. Stated on `DocumentHandler.apply`, on
`RowBinding`, and at length in the module's README under "Where a validation rule goes".

## The shape of the gap

Three of the thirteen — the removal window, an over-large version increment, and whether a
changelog entry is true — are genuinely unmechanisable, and are listed so that their absence reads
as a decision. Of the rest, most are the same failure: **the harness is good at stopping an agent from touching the
wrong file, and poor at stopping it from changing the rules.** Path rules are positional and a deny
list expresses them exactly. "Do not make the check weaker" is a statement about intent, and the
only mechanical form it has is a golden list that makes weakening visible rather than impossible.
That is the next thing worth building.
