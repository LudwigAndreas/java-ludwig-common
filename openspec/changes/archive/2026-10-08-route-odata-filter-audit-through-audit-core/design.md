## Context

See `proposal.md` - Why. The design-relevant facts:

- `audit-core` has zero in-repo dependencies, by design, which is what makes it safe for any module to
  depend on. It provides `AuditEvent`, `AuditSink`, `AuditFailurePolicy`, `FailurePolicyAuditSink` and
  `Redaction.MASK`.
- Nine modules already carry the shape this change copies: `IdempotencyAuditEvent`, `ExportAuditEvent`,
  `IngestAuditEvent`, `FileActionAuditEvent`, `ReconciliationAuditEvent`, `DeadLetterAudit` and the
  `rest-client` recorder. The pattern is a record in an `audit` package with `toAuditEvent()`, called from
  the module's service.
- `ODataFilterService` currently publishes `new FilterAppliedEvent(entityType, filter, predicate.toString(),
  callerRoles)` through an `ApplicationEventPublisher` that may be null, after the parse succeeds and before
  the result is returned.
- The parse already produces the validated AST (`FilterNode`) and the resolved
  `Map<String, FilterFieldPolicy>`, so the property paths and operators a filter named are available without
  re-parsing anything.
- `ODataFilterService` is used by `export-spring-boot-starter` on a worker thread with no HTTP request, so
  nothing here may assume a request context.

## Goals / Non-Goals

**Goals:**

- The filter trail reaches the same sink, under the same failure policy, as every other module's.
- No caller-supplied value can reach the trail from this module.
- The gap in `RuleGroup.AUDIT` that let this stand closes, so the next module cannot repeat it.

**Non-Goals (design level):**

- Changing what `audit-core` offers.
- Auditing the rejection path.
- Deciding for a deployment whether filtered reads are worth auditing. The event is produced; the sink and
  the category's policy decide what happens to it.

## Decisions

### D1: The event records paths and operators, never literals

`FilterAppliedEvent`'s `resolvedPredicate` string is replaced by a summary derived from the AST: the
`/`-joined property paths the filter named and the comparison/function operators applied to them, in a
stable order. The caller's literal values are not included in any form - not hashed, not truncated.

Alternatives considered: (a) keep the predicate string and mask the literals inside it - requires parsing a
QueryDSL `toString()` to find them, which is a format no contract covers, and a miss is a leak; (b) carry
the raw `$filter` string the caller sent, which the event already does as `rawFilter` - **this is removed
too**, for the same reason: `price gt 100 and email eq 'a@b.c'` is the literal values, verbatim. What
survives is `price gt, email eq`.

`audit-envelope` requires attributes to be redacted at construction, and this satisfies it by construction
rather than by masking: there is nothing to mask, because no value is collected. `Redaction.MASK` is used
only where the summary would otherwise have to name a value.

### D2: The sink is called from `ODataFilterService`, not from a listener this module registers

The module could keep publishing the application event and add its own `@EventListener` that forwards to the
sink. Rejected: that is a second hop that can be switched off by a consumer excluding an auto-configuration,
and it makes the trail depend on the event publisher being non-null - which it may not be, because
`ODataFilterService` is constructible with a null `ApplicationEventPublisher` today. The sink call is
unconditional and sits where the other nine modules put theirs.

### D3: `record` is not wrapped in try/catch, and the sink is optional at wiring time

Per `audit-envelope`, no try/catch: `AuditFailurePolicy` decides whether a sink failure fails the caller, and
a catch here would override a deployment's configuration. The sink is injected and the auto-configuration
supplies `audit-core`'s no-op or configured sink, so a consumer that wants no trail configures that rather
than this module defending against a missing bean.

A question this raises and the design answers deliberately: a filtered read now runs the sink inline, so a
`FAIL_OPERATION` category would fail a query on a sink outage. That is the deployment's choice to make for
the category it files filter events under, and it is the same exposure every other audited operation in the
platform already has. The README must say which category this module uses and that the policy for it is the
deployment's.

### D4: The application event survives, demoted

`FilterAppliedEvent` keeps being published, because an in-process listener is a reasonable thing for a
service to want and removing it breaks consumers for nothing. Its javadoc stops saying "to build an audit
trail" and says what it now is: a notification that a filter was applied, whose audit copy has already gone
to the sink. The same sentence is corrected in both READMEs.

### D5: The ArchUnit gap is closed by a rule about `audit` packages, not about events

The gap is that `RuleGroup.AUDIT` only catches a module that declares a sink interface or an audit-named
logger - a module that declares neither and *also calls no sink* passes. The new rule: a type declared in a
package named `audit` must either produce an `AuditEvent` (declare a `toAuditEvent()` or a method returning
one) or be handed to an `AuditSink` somewhere in its module.

Alternative considered: a rule forbidding `ApplicationEventPublisher` in a module with no `AuditSink`
dependency. Rejected - it is both too broad (application events have many legitimate uses) and too narrow (a
module could publish through any mechanism). Anchoring on the `audit` package name targets the actual
declaration of intent: a type a module put in a package called `audit`.

This is ArchUnit's and not Checkstyle's because it is a question about types, packages and method return
types, all of which bytecode carries - the enforcement triad gives structure to ArchUnit.

### Dependency-direction check

**Does `audit-core` already depend on `odata-filter-spring-boot-starter`, directly or transitively?**
`project-index.json`'s `inRepoDependencies` for `audit-core` is `[]` - it has zero in-repo dependencies,
deliberately, which is the same property that lets thirteen other modules depend on it. So adding
`odata-filter-spring-boot-starter` -> `audit-core` cannot produce a cycle.

**Does the new dependency reach any module that previously did not have `audit-core`?** The four dependents
of `odata-filter-spring-boot-starter` are `crud-service-example`, `export-spring-boot-starter`,
`notification-service` and `user-settings-spring-boot-starter`. `export` already depends on `audit-core`
directly. The other three acquire it transitively, which is what a starter taking a platform library is
expected to do, and `audit-core` has no transitive weight of its own to pass on.

**Does `architecture-rules` gain a dependency?** No. The new rule uses ArchUnit and the `audit-core` type
names as strings, which is how `RuleGroup.AUDIT`'s existing rules are written; `architecture-rules` does not
depend on `audit-core` and must not start.

### Which of the three POMs changes

The module POM only - one `<dependency>` on `audit-core`, version from `ludwig-bom`, which every module
already imports. `ludwig-bom` does not change, because `audit-core` is an in-repo artifact already managed
there. The root `pom.xml` and `ludwig-service-parent` do not change. Because a POM changed,
`scripts/manifest.sh build` runs before the gate and `scripts/manifest.sh stale` must exit 0 after.

### New conventions and the check that enforces each

| Convention | Check | Owner |
|---|---|---|
| A type in an `audit` package produces an `AuditEvent` or is handed to the sink | new rule in `architecture-rules`' existing `RuleGroup.AUDIT` (D5) | `architecture-rules` (structure) |
| No caller-supplied literal reaches the event | unit test over the parser's own test filters asserting the summary contains the paths and operators and none of the literals, including a filter whose literal is an e-mail address | tests |
| `record` is not wrapped in try/catch | the existing `RuleGroup.AUDIT` rule, which already covers this | existing |
| The event is published *and* recorded | unit test asserting both happen for one parse | tests |

The "no literal reaches the event" rule cannot be checked statically - it is a property of a computation's
output, not of a type graph or a source line - and the test's javadoc says so, per "encode every rule twice".

### This introduces no second implementation of a centralised mechanism

It removes one. The sink, the envelope, the failure policy and the mask are all `audit-core`'s; nothing new
is declared.

## Risks / Trade-offs

- **A filtered read can now fail on a sink outage, if the deployment files filter events under a
  `FAIL_OPERATION` category.** → That is the deployment's decision and the same exposure every other audited
  operation has. The README states which category is used and that the policy is configuration.
- **Replacing a record component breaks any consumer listening for the predicate string.** → Intended: such
  a consumer was reading caller data out of an event. A major release, and the changelog names it.
- **Three modules acquire `audit-core` transitively.** → It has zero in-repo dependencies and a small
  surface; this is the direction the platform's dependencies are meant to run.
- **The new ArchUnit rule anchors on a package named `audit`, so a module could evade it by renaming the
  package.** → True, and a rename would be visible in review in a way that the current silence is not. The
  rule's javadoc says the anchor is the module's own declaration of intent and that evading it by renaming is
  the one case no check can catch.
- **An audit write per filtered read is a volume the trail did not previously carry.** → It is one event per
  search request, not per row, and the deployment chooses the sink. Noted so that whoever enables it sizes
  for it.
