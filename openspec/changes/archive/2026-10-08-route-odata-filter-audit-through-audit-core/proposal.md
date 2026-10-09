## Why

`odata-filter-spring-boot-starter` is the one module in the reactor whose audit trail does not reach the
platform's audit sink. Nine modules - `idempotency`, `export`, `file-ingest`, `file-action`,
`reconciliation`, `messaging`, `rest-client`, `security` and `observability` - follow the documented shape:
a typed event record in an `audit` package with a `toAuditEvent()`, handed to `audit-core`'s single
`AuditSink`. This module has `ru.ludwigandreas.odatafilter.audit.FilterAppliedEvent` with no
`toAuditEvent()`, no `AuditSink` and no dependency on `audit-core`; it publishes a raw Spring
`ApplicationEvent` and its README tells the consumer to "write an `@EventListener` for it to build an audit
trail".

So "who queried what" - which is what that event is for, and the README says "e.g. for compliance logging
in a multi-tenant system" - lands in the platform trail only if each consuming service writes a listener
that forwards it, identically, per service. ArchUnit's `RuleGroup.AUDIT` does not fire, because an
application event is not an audit SPI declaration and the package is not a logger name, so the build is
green on a module that opts out of the one mechanism by not using it.

The second half of the problem is worse than the routing. The event carries
`predicate.toString()` - the resolved QueryDSL predicate, including the caller's literal values. A filter
on an annotated e-mail, phone or document-number field puts that value into the event, and there is no
`Redaction` anywhere near it. `audit-envelope` is explicit that an `AuditEvent`'s attributes are redacted
at construction and that the trail records which object was touched and never its contents - a trail
"retained for years and read by people who are not entitled to the data it guards". Today this event is the
one that would fail both requirements, and the only reason it has not is that nothing is listening.

## What Changes

- **`FilterAppliedEvent` gains a `toAuditEvent()`** and `ODataFilterService` hands the result to
  `audit-core`'s `AuditSink`, as the other nine modules do. The record stays the authoring surface.
- **The resolved predicate and the raw filter both stop being carried.** The event records the entity type,
  the property paths the filter named and the operators applied to each - which object was queried and how -
  and no literal value in any form. Not hashed and not truncated either: a hash is reversible for a value
  drawn from a small set, which most enumerations are, and a truncation leaks the identifying prefix. There
  is consequently nothing for `Redaction.MASK` to mask, which is the honest version of "the attributes are
  already redacted at construction".
- **`audit-core` gains one category constant, `QUERY`.** A filtered read is not an authorization decision,
  so it does not belong under `ACCESS`.
- **`record` is not wrapped in try/catch.** Whether a sink failure fails the query is
  `AuditFailurePolicy`, resolved from the deployment's configuration, and a `catch` in this library would
  override it.
- **The Spring application event is kept**, because an in-process `@EventListener` is a legitimate thing for
  a service to want and removing it breaks consumers for no gain. It is no longer the *audit* mechanism, and
  its javadoc and the README stop describing it as one.
- **BREAKING: `FilterAppliedEvent`'s `resolvedPredicate` and `rawFilter` components are both replaced** by
  the paths-and-operators summary. A consumer listening for either was reading caller data out of an event,
  which is the defect - `rawFilter` is the caller's literals verbatim just as much as the predicate string
  is.
- **`odata-filter-spring-boot-starter` takes a new in-repo dependency on `audit-core`**, which has zero
  in-repo dependencies and therefore cannot produce a cycle.

## Capabilities

### New Capabilities

None.

### Modified Capabilities
- `audit-envelope`: its "one audit sink, and no module-local audit SPI" requirement is extended so that
  publishing an application event *as* a module's audit mechanism is named as a way of not using the sink,
  and the gap in `RuleGroup.AUDIT` that let it pass is recorded. The existing requirements on redaction and
  on payloads are not changed - this change brings a module into compliance with them rather than altering
  them.

## Impact

### Modules touched

| Module | POM tier | In-repo dependents the gate must run |
|---|---|---|
| `odata-filter-spring-boot-starter` | library (starter), parented by the reactor root `common` | `crud-service-example`, `export-spring-boot-starter`, `notification-service`, `user-settings-spring-boot-starter` |
| `architecture-rules` | library, parented by the reactor root `common` | `crud-service-example` (test), `notification-service` (test), and every module enabling its rule sets |
| `audit-core` | library, parented by the reactor root `common` | `audit-spring-boot-starter`, `export-spring-boot-starter`, `file-action-spring-boot-starter`, `file-ingest-spring-boot-starter`, `hot-reload-spring-boot-starter`, `idempotency-spring-boot-starter`, `messaging-spring-boot-starter`, `observability-spring-boot-starter`, `odata-filter-spring-boot-starter`, `outbox-spring-boot-starter`, `pat-spring-boot-starter`, `reconciliation-spring-boot-starter`, `rest-client-spring-boot-starter`, `security-spring-boot-starter` |

`audit-core` gains **one `AuditCategories` constant and nothing else** - `QUERY`. Its envelope, its sink,
its failure policy and `Redaction.MASK` are untouched and do what is needed as they stand.

An earlier draft of this proposal said `audit-core` was not touched at all, on the grounds that changing
it would be evidence this module's event is the wrong shape. That reasoning holds for the *mechanisms* and
not for the category vocabulary: `AuditCategories` is a list each module contributes to, and `IDEMPOTENCY`,
`MESSAGING` and `CREDENTIAL` were each added by the module that needed them, with javadoc stating what
distinguishes them from their neighbours. Filing a read under `ACCESS` instead was considered and rejected:
that constant is documented as "Authorization decisions", `CREDENTIAL`'s javadoc leans on exactly that
distinction, and an auditor filtering `category=access` for authorization decisions should not get every
successful search mixed in.

### Shared contracts in `openspec/specs/`

- **`audit-envelope`** - modified, as declared above.
- **`enforcement-triad`** - not modified. The new check is an ArchUnit rule in the existing
  `RuleGroup.AUDIT`, which is `architecture-rules`' existing ownership of structure and dependencies; it is
  an instance of the triad, not a change to it.
- **`i18n-bundles`** - not touched. An audit event is not user-facing text.
- **`data-access`**, **`problem-detail-pipeline`**, **`cache-purpose`**, **`long-running-operations`**,
  **`module-dependency-direction`**, **`repository-layout`**, **`pom-topology`**, **`test-layout`** - not
  touched. The new in-repo dependency runs in the permitted direction and adds no cycle, which
  `module-dependency-direction` already governs without amendment.
- **`api-evolution`** - not modified; it governs the release. Replacing a record component is
  binary-incompatible, which that spec puts at a major increment. This change targets the same major release
  as `add-odata-query-execution`.

### Dependencies

One new in-repo dependency: `odata-filter-spring-boot-starter` -> `audit-core`, compile scope. No new
third-party dependency. One POM change, in the module POM only - `audit-core`'s version comes from
`ludwig-bom`, which every module already imports, so `ludwig-bom` does not change. Because a POM changes,
`scripts/manifest.sh build` runs before the gate.

## Non-goals

- **Changing any of `audit-core`'s mechanisms.** The envelope, the sink, `AuditFailurePolicy` and
  `Redaction.MASK` are sufficient as they stand, and a change that needed to widen one of them would be
  evidence this module's event is the wrong shape. The one addition is a category constant, which is
  vocabulary rather than mechanism - see the module table above.
- **Auditing rejected filters.** A rejection is already a metric (`odata.filter.rejected`) and a problem
  response. Writing every malformed filter to a trail retained for years turns a probing client into
  storage growth, and the metric is the right instrument for the volume question.
- **Removing the Spring application event.** See above: it stays, demoted from audit mechanism to in-process
  hook.
- **Making the audited detail configurable.** A deployment that could widen the event to include literal
  values would be a deployment that could put caller data in the trail, which is the defect being fixed.
- **Auditing every read in the platform.** This change routes the event that already exists. Whether a
  filtered read is auditable at all is a decision each deployment makes by configuring the category's
  `AuditFailurePolicy` and its sink, not something this module widens.
