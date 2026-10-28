## Context

This crosses `security-spring-boot-starter` (the evaluation) and `reconciliation-spring-boot-starter` (the first
adopter), and changes two shared contracts. Facts it builds on:

- `DataScopeProvider` and `CompositeDataScopeProvider` union policy defaults with the identity projection's
  per-subject grant rows;
- `DataScope` evaluates to `ALL`, `NONE`, or OR-of-AND restrictions;
- `PolicyDataScopeProvider` parses policies at startup;
- `AdminAccessEvaluator` decides `@AdminAccess` and publishes `AuthorizationDeniedEvent` on refusal.

## Goals / Non-Goals

**Goals:**
- Per-object narrowing of an operation, using the data-scope model as it stands.
- A deployment that does not opt in sees no change.

**Non-Goals:** see the proposal.

## Decisions

1. **Evaluate in memory against the `DataScope`, not through a predicate.** Administrative objects such as task
   names come from a registry, not rows, so the `DataScopePredicateFactory` path does not apply. A new
   `DataScope.admits(ScopeDimension, String)` answers for a single value:
   - `ALL` admits everything;
   - `NONE` admits nothing;
   - `RESTRICTED` admits when any alternative's `object` set contains the value and no other dimension is
     restricted.

   An alternative that restricts another dimension (`tenant`) does not admit, because an administrative object has
   no tenant. This keeps the evaluation fail-closed.
2. **`@AdminObject` on a handler parameter.** `AdminAccessEvaluator`, which already runs before the handler, reads the
   annotated parameter's resolved value from the handler method's arguments. It does not reparse the request. If the
   area and tier have a policy entry (`DataScopeRegistry`/policy lookup for `admin.<area>`), it asks
   `DataScopeProvider.scopeFor(principal, "admin.<area>", tier)` and checks `admits(OBJECT, value)`.

   On refusal, it publishes the same `AuthorizationDeniedEvent` and throws one of two exceptions:
   - `AccessDeniedException` (`403`) on operate and privileged;
   - the area's not-found (`404`) on read.

   The read case uses a new `AdminObjectNotFoundException`, mapped by the problem pipeline to `404` with the same
   body as a missing object.
3. **`AdminObjectScope` for lists.** Its single method, `filter(area, tier, Collection<String> names)`, uses the same
   scope lookup. Reconciliation's task listing calls it.
4. **Policy validation.** Startup fails for an `admin.*` resource whose role entry is anything other than `ALL` or
   `NONE`, and for an `admin.<area>` whose area is not in the `AdminOperationCatalog`, since a typo would otherwise
   silently scope nothing. Both checks are added to `DataScopePolicyValidator`.
5. **`OBJECT` in `BUILT_IN`.** This lets `identity-admin` grants name dimension `object` under strict tokens without
   a deployment turning strictness off globally.

**Dependency-direction check.** Does `security-spring-boot-starter` or `reconciliation-spring-boot-starter` gain an
in-repo dependency? No. Reconciliation already depends, optionally, on `security-spring-boot-starter`. No edge is
added.

**POMs.** No POM changes: not the root `pom.xml`, `ludwig-bom` or `ludwig-service-parent`.

**Mechanical checks.**
- The new convention is that an area offering per-object scoping marks its object parameter with `@AdminObject`.
  An ArchUnit rule in `architecture-rules` (`RuleGroup.ADMIN`, `admin-object-is-a-path-variable`) enforces that
  `@AdminObject` appears only on a parameter that is also `@PathVariable`.
- The policy-shape rule is enforced at startup (decision 4).

## Risks / Trade-offs

- [Per-subject grants for many people become tedious.] → This is the stated non-goal. If role-level object lists
  are needed later, they get their own change. The grant API takes an expiry, which suits on-call style access.
- [A grant's effect lags on other replicas by the data-scope cache TTL.] → This is the same, already documented,
  behaviour as every other data-scope grant.
