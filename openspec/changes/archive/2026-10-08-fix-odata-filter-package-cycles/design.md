## Context

See `proposal.md` - Why. The design-relevant facts, all from the first run of
`odata-filter-spring-boot-starter`'s new `ArchitectureTest`:

- `cycles.modules-are-free-of-cycles` reports **16 violations across roughly eleven distinct cycles**. They
  share two roots: `config -> core -> policy -> config`, and `core <-> validation <-> policy`.
- One cycle, `core <-> querydsl`, was **already fixed** in `route-odata-filter-audit-through-audit-core` by
  moving `ODataQueryExecutor`, `ODataSearch` and `ODataPage` out of `querydsl` into a new `execution`
  package. That fix is the template this change follows and the evidence that the approach works: the edge
  was removed by moving the type that pointed the wrong way, not by weakening anything.
- The packages are `ast`, `annotation`, `audit`, `config`, `core`, `exception`, `execution`, `metrics`,
  `parser`, `policy`, `querydsl`, `security`, `validation`, `web`.
- `config` holds both `ODataFilterProperties` (a value every other package reads) and the four
  auto-configuration classes (which reference every other package to build beans). That single package
  playing both roles is what puts `config` on both sides of a cycle, and it is almost certainly the whole of
  the `config -> ... -> config` family.
- `file-ingest-spring-boot-starter` hit this exact problem and solved it: its properties live in `config`
  and its wiring in `autoconfigure`, and its `architecture-rules.properties` says so in a comment, calling
  it "a correctness fix rather than a preference".

## Goals / Non-Goals

**Goals:**

- Both disabled rules switched back on in this module's `ArchitectureTest`, with nothing else disabled in
  their place.
- Every public type keeps its behaviour; only its declaring package may change.

**Non-Goals (design level, beyond the proposal's):**

- Introducing a package whose only purpose is to break a cycle without naming a real role. A package named
  `common`, `shared`, `internal` or `util` is how a cycle becomes a knot.
- Changing the `ODataFilterService` constructor or any bean's type, so the services' wiring is untouched.

## Decisions

**The trace has now been taken, and it is recorded in `cycles-before.txt`.** D1 is confirmed exactly; D2
predicted a cycle that does not exist and is withdrawn. The amendments are written into D1 and D2 below
rather than appended, so the document reads as what is true.

**What the trace showed.** 20 violations, not the 16 seen before - the `execution` and `metadata` packages
added since have widened the same family. Every one of the 20 cycles **starts and ends at `config`**, and
there is no cycle anywhere else in the module. The inbound edges to `config` are exactly three, from `core`,
`web` and `policy`, and every one of them is a reference to `ODataFilterProperties` - verified by grep: those
three classes contain the module's only three `import ru.ludwigandreas.odatafilter.config.*` statements
outside `config` itself. So one class is on the wrong side of one boundary, and moving it removes all twenty
cycles at once.

### D1: Split `config` into properties and wiring, following `file-ingest` - CONFIRMED BY THE TRACE

`ODataFilterProperties` moves to its own package; the auto-configuration classes stay in `config`. Nothing
then depends on `config` at all - the wiring is a leaf, which is what wiring should be - and the whole
`config -> ... -> config` family of cycles disappears with one move.

**The trace confirms this is the whole fix, not part of it.** All 20 cycles route through `config`, and the
only inbound references are the three `ODataFilterProperties` imports in `core`, `web` and `policy`.

Alternative considered: leave the properties where they are and cut the inbound edges instead, by passing
primitives into each collaborator rather than the properties object. Rejected: it means every policy and
validation type taking three or four constructor parameters where it now takes one, which is a worse API in
exchange for the same acyclicity.

`file-ingest` names its wiring package `autoconfigure` and keeps `config` for properties - the opposite
assignment. This design follows the *split* and not the naming, and the original reason given was that
renaming `config` would change
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` "for no gain". That
reason was too weak, and the real one is stronger: **an auto-configuration class's fully-qualified name is
configuration API.** A deployment switches one off with
`spring.autoconfigure.exclude=ru.ludwigandreas.odatafilter.config.ODataFilterAutoConfiguration`, which is a
string in a YAML file that no compiler checks - so renaming that package breaks an exclusion silently, at
runtime, in whichever environment set it. Moving `ODataFilterProperties` is binary-breaking and
compile-visible; renaming the wiring is neither. The cheaper-looking move is the dangerous one.

The inconsistency with `file-ingest` therefore stands, deliberately, and is recorded here so that whoever
next reads both modules is not surprised by it.

### D2: WITHDRAWN - there is no `core <-> validation <-> policy` cycle

This decision predicted that `core.ODataFilterService` calls `validation`, which reads `policy`, which is
read back by `core`, and proposed either handing `validation` what it needs or merging it with `policy`.

**The trace shows no such cycle.** `validation` appears in four of the twenty cycles
(`config -> ... -> core -> validation -> policy -> config`) and in every one of them it is a *pass-through*:
the back edge is `policy -> config`, not anything returning to `core`. `policy` does not depend on `core` at
all. So `core -> validation -> policy` is a straight line, which is the shape it should be, and nothing needs
cutting.

Recorded rather than deleted because the prediction was wrong in a useful way: it is exactly the kind of
cycle a reader *expects* in a module with a parser, a policy and a validator, and the trace says this module
does not have it. Merging `validation` into `policy` would have been a restructuring performed for no
reason - which is what taking the trace first was for.

### D3: `ODataFilterProperties` gets `@Validated` only if an implementation can be taken safely

The constraint already discovered: `jakarta.validation-api` on this module's classpath activates
`web-core`'s `LocalValidatorFactoryBean` wiring, which then needs an implementation, which this module does
not have - that is the failure that cost 15 integration tests in `add-odata-query-execution` and is why the
API jar is excluded from the springdoc dependency in this module's POM.

So the options are: take `hibernate-validator` as a real (optional) dependency and declare the constraints
the properties actually have (`maxDepth >= 1`, `maxPageSize >= 1`, and so on, which are currently enforced
nowhere); or refuse the rule for this module with a recorded reason. **The first is preferable** because
those bounds are real and currently unchecked - a `max-page-size` of 0 is accepted today and makes every
query fail - but it must be verified that adding the implementation does not reactivate the web-core wiring
in a consumer that lacks one. The task list makes that verification its own step rather than an assumption.

Alternative considered: fix `web-core`'s condition instead, so it keys on a validation *implementation*
rather than the API. That is the right fix for the latent defect recorded in
`add-odata-query-execution`'s receipt, and it is a `web-core` change with sixteen dependents - a separate
change, not a step inside this one.

### Dependency-direction check

**Does this change add any in-repo dependency?** No. Every move is between packages of one module.
`project-index.json`'s `inRepoDependencies` for `odata-filter-spring-boot-starter` is
`[audit-core, test-support, web-core-spring-boot-starter, architecture-rules]` and stays exactly that.

**Does D3 add a third-party dependency?** Possibly one, `hibernate-validator`, optional. If taken, its
version goes in `build/ludwig-bom/pom.xml` and nowhere else, and the task list verifies it does not reach a
consumer's classpath in a way that activates `web-core`'s validator wiring.

### Which of the three POMs changes

The module POM only, and only under D3 (an optional `hibernate-validator`), plus `build/ludwig-bom/pom.xml`
for its version. Under D1 and D2 no POM changes at all. The root `pom.xml` and `ludwig-service-parent` do
not change either way. If any POM changes, `scripts/manifest.sh build` runs before the gate.

### New conventions and the check that enforces each

| Convention | Check | Owner |
|---|---|---|
| The package graph stays acyclic | `cycles.modules-are-free-of-cycles`, re-enabled | `architecture-rules` |
| Bound properties are validated | `configuration-properties.configuration-properties-are-validated`, re-enabled | `architecture-rules` |
| Nothing depends on the wiring package | the cycles rule covers it; no separate rule | `architecture-rules` |

This change introduces no convention that needs a *new* check. That is the point of it: two checks that
already exist stop being switched off.

### This introduces no second implementation of a centralised mechanism

Nothing is added. Types move between packages and, under D3, a validation implementation this module
already behaves as if it had.

## Risks / Trade-offs

- **A package move of a public type breaks a consumer's imports.** → The release is already major, and the
  four in-repo dependents are in the gate. An external consumer gets the move named in the changelog.
- **The trace may show the cycles are not what D1 and D2 predict.** → That is why the trace is task 1 and
  why these decisions say so in writing. An amended decision with the trace quoted is a good outcome; a
  restructuring that followed a guess is not.
- **Re-enabling `configuration-properties-are-validated` could reactivate the web-core validator trap.** →
  Verified as its own task, with the previous change's failure as the regression test to repeat.
- **A restructuring with no behaviour change is exactly the kind that gets reviewed casually.** → Every task
  names the gate command, and the four dependents' integration tests are the real check: if the filter, the
  predicate, the page and the errors are unchanged, a test that only needed an import changed is the
  expected diff and anything else is a finding.

## Open Questions

- Whether `validation` and `policy` should merge (D2). Deferrable: it changes no spec and no task boundary,
  and the trace in task 1 answers it with evidence rather than taste.
