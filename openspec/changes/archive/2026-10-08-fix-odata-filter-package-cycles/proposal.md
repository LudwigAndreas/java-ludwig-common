## Why

`odata-filter-spring-boot-starter` has roughly eleven distinct package cycles, and until
`route-odata-filter-audit-through-audit-core` gave the module its first `ArchitectureTest` nobody had
seen them: a service's `@AnalyzeArchitecture` run analyses only that service's own packages, so no test
in the reactor had ever applied the shared rule library to `ru.ludwigandreas.odatafilter`. The rule was
switched off there as named debt, with the reason at the point of the disable and a pointer to this
change.

The cycles are not cosmetic. `config → core → policy → config` means the configuration package and the
policy package each need the other to be loadable, so neither can be read, tested or reasoned about on
its own; `core ↔ validation ↔ policy` means the same for the three packages that between them decide
whether a caller's filter is allowed, which is this module's security boundary. A cycle is also how a
module stops being splittable: the parser and the policy registry are independently useful - `export`
uses the service with no persistence unit at all - and a cycle is what makes "use only this part"
impossible to express.

The same `ArchitectureTest` also disabled
`configuration-properties.configuration-properties-are-validated`, for a reason that has nothing to do
with cycles and that this change settles separately: `ODataFilterProperties` carries no `@Validated`, and
the naive one-annotation fix is a trap, because `@Validated` needs a validation *implementation* and this
module's POM deliberately excludes the transitive `jakarta.validation-api` that springdoc drags in - that
API jar alone activates `web-core`'s validator wiring and failed every integration test here.

## What Changes

- **The package graph becomes acyclic**, and both disabled rules are switched back on in
  `odata-filter-spring-boot-starter`'s `ArchitectureTest`. Re-enabling them is the deliverable; the
  restructuring is how.
- **The direction of the remaining edges is stated and enforced.** The design settles which package may
  depend on which; this proposal does not pre-empt it beyond the one edge already known to be wrong, which
  is `config` being depended *upon* rather than only depending.
- **`ODataFilterProperties` is either validated or the rule is refused with a recorded reason.** Both are
  acceptable outcomes and the design picks one: annotate it and take a validation implementation at the
  scope that does not reactivate the web-core wiring, or state in the spec why a property class in a
  library that ships no constraints is a case the rule should not cover.
- **No behaviour changes.** Every public type keeps its contract; what moves is which package declares it.
  Package moves of public types are breaking for a consumer's imports, which the release already is.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

None. A package graph is not a behaviour: no requirement in `openspec/specs/` describes which package
declares a type, and inventing one to satisfy validation would be writing a spec about an implementation
detail. This change sets `skip_specs: true` in its `.openspec.yaml`, which is what that marker is for -
and the two re-enabled ArchUnit rules are the mechanical check that replaces a spec here, which is the
`enforcement-triad`'s own division of labour.

## Impact

### Modules touched

| Module | POM tier | In-repo dependents the gate must run |
|---|---|---|
| `odata-filter-spring-boot-starter` | library (starter), parented by the reactor root `common` | `crud-service-example`, `export-spring-boot-starter`, `notification-service`, `user-settings-spring-boot-starter` |

Any package move of a public type reaches the four dependents through their imports, which is why they
are in the gate rather than merely likely to be fine.

### Shared contracts in `openspec/specs/`

- **None modified.** See Capabilities.
- **`enforcement-triad`** - not modified, and relevant: the two rules being re-enabled are ArchUnit's,
  which is the tool that owns structure and dependencies. Nothing here belongs to Checkstyle or SonarQube.
- **`api-evolution`** - not modified; it governs the release. Moving a public type between packages is
  binary-incompatible, which that spec puts at a major increment - the same major release as
  `add-odata-query-execution` and `route-odata-filter-audit-through-audit-core`.

### Dependencies

No new in-repo dependency and no new third-party dependency expected. Whether `ODataFilterProperties`
needs a validation implementation is the one open question that could change that, and the design settles
it; if it does, the version belongs in `ludwig-bom` and the scope must not reactivate `web-core`'s
validator wiring in a module with no validator.

## Non-goals

- **Changing any public behaviour.** A caller's filter, the predicate it produces, the page it returns and
  every error it can raise stay exactly as they are. If a test has to change for a reason other than an
  import, that is a signal the restructuring went wrong.
- **Fixing cycles in other modules.** This change does not enable the rule library on any other module
  that lacks it. Finding out how many others are in the same position is worth doing and is not this.
- **Renaming packages to silence the rule.** The audit rule's javadoc already names a package rename as the
  evasion no check can catch; doing it here deliberately would be worse than the debt.
- **Splitting the module.** The cycles make a split impossible, and removing them makes one *possible* -
  not required. Whether the parser should ship separately from the QueryDSL translator is a separate
  question that this change deliberately leaves open rather than answering by accident.
- **Re-enabling the two rules without fixing the findings.** The point is the findings, not the green tick.
