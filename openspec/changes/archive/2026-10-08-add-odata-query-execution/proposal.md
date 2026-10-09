## Why

`odata-filter-spring-boot-starter` stops one step short of being usable. It hands a consumer a
QueryDSL `Predicate` and a Spring Data `Sort` of dotted strings, and then every consumer writes the
same thirty lines to finish the job. `ProductQueryRepositoryImpl` and `DeliveryQueryRepositoryImpl`
hold a byte-for-byte duplicate of a `static final PathBuilder ROOT`, an `orderSpecifiers(Sort)`, a
reflective `orderSpecifier(String, boolean)` carrying the same
`@SuppressWarnings({"rawtypes","unchecked"})`, the same `PageableExecutionUtils.getPage(...)` call and
their own private `count()` - duplicating, by hand, the very path traversal `PredicateBuilder.typedPath`
already performs inside the module. Above the repository, every controller re-declares the same four
`@RequestParam(name = "$filter"|"$orderby"|"$top"|"$skip")` parameters and every service re-declares a
`XSearchCriteria` record that is structurally `(filter, orderBy, top, skip)`.

The paging contract is also incoherent end to end. `OffsetPageRequest` exists precisely because
OData's `$skip` is an absolute offset that need not align to `$top`, yet its `getPageNumber()` is
`offset / limit` with integer truncation, and that synthesised number is what `PageResponse.of(page)`
publishes as `"page"`. At `$top=20`, both `$skip=20` and `$skip=25` serialise as `"page": 1`, and
`PageResponse` has no `offset` member, so a caller paging by `$skip` cannot read its own position
back out of the response. Separately, both repositories pay for an unconditional `SELECT COUNT(*)` on
every search - usually the slowest part of a deep-paged filtered request - with no way for a caller
to decline it.

This change closes the gap between "the filter is parsed" and "the page is on the wire", which is the
work a service author should not be doing at all. It lands in a **major release**, so the module's
public surface is corrected rather than extended around.

## What Changes

- **An `ODataQueryExecutor` in `odata-filter-spring-boot-starter`.** Given an entity type, the
  caller's options and a hook for the query root, it resolves the policy, builds the predicate, turns
  the resolved `Sort` into `OrderSpecifier<?>[]` against the same `PathBuilder` root the predicate
  uses, applies offset and limit, and runs the count conditionally. Both services delete their
  duplicated ordering and counting code.
- **`OrderSpecifier` construction becomes part of the module's public surface**, so a repository that
  wants to keep driving its own `JPAQuery` (the fetch-join case in `ProductQueryRepositoryImpl`) still
  does not hand-write the reflective path walk.
- **An `ODataQueryOptions` request record** - the four OData query options plus `$count` as a single
  springdoc-describable parameter object, naming no JPA entity and carrying no `Predicate`. It
  replaces the per-service `XSearchCriteria` records and the four repeated `@RequestParam`
  declarations. This is the one-liner the deprecated `ODataQueryArgumentResolver` promised without any
  of the four defects the module README correctly lists against it.
- **BREAKING: `ODataFilterService.parse` takes `ODataQueryOptions`** instead of four positional
  parameters, two of which are `Integer` and two of which are `String`. The present signature
  `parse(Class, String, Integer, Integer, String)` lets `filter` and `orderBy` be transposed silently
  at the call site; the record cannot be.
- **BREAKING: `ODataQueryArgumentResolver` and `odata.filter.web.argument-resolver-enabled` are
  deleted**, along with the `WebMvcConfigurer` bean that installed the resolver. It was deprecated and
  off by default with no replacement; `ODataQueryOptions` is the replacement, so the deprecation ends
  here rather than being carried into a second major.
- **BREAKING: `PageResponse` gains an `offset` member** and `OffsetPageRequest.getPageNumber()` is
  corrected, so the envelope never publishes a truncated page index as if it were the caller's
  position. Source-breaking for anyone calling the canonical constructor directly, and an added member
  in the JSON.
- **`$count` is parsed and honoured.** `$count=false` causes the executor to skip the count query and
  return an envelope with no total. The module README's current position - that the flag bought the
  caller nothing - is true only while nothing executes the query; the executor is the one place that
  can honour it.
- **BREAKING: `ODataQuery.predicate()` returns `Optional<Predicate>`** rather than
  `Expressions.TRUE` for "no filter". Today `ODataReportFilterParser` wraps the result in
  `Optional.ofNullable` and always gets `Optional.of(TRUE)`, so a report with no filter has an
  unconditional `AND true` appended and the `Optional` return type promises a distinction the service
  cannot make. The absence of a filter becomes observable, and that dead branch in `export` is fixed.

## Capabilities

### New Capabilities
- `odata-query-contract`: how a caller's OData query options reach the database and how the resulting
  page reaches the wire - who may parse the options, where the predicate may be executed, what the
  response envelope states about the caller's position, and when a count query is paid for.

### Modified Capabilities
- `data-access`: adds the requirement that a repository does not hand-write the dynamic property-path
  walk for `$orderby`. The existing QueryDSL-only requirement is unchanged and this is a new
  requirement under the same capability, because the duplicated reflective `PathBuilder` walk is the
  one piece of dynamic path construction the QueryDSL-only rule permits and therefore the one place
  it needs to say who is allowed to write it.

## Impact

### Modules touched

| Module | POM tier | In-repo dependents the gate must run |
|---|---|---|
| `odata-filter-spring-boot-starter` | library (starter), parented by the reactor root `common` | `crud-service-example`, `export-spring-boot-starter`, `notification-service`, `user-settings-spring-boot-starter` |
| `web-core-spring-boot-starter` | library (starter), parented by the reactor root `common` | `audit-spring-boot-starter`, `crud-service-example`, `db-core`, `export-spring-boot-starter`, `file-action-spring-boot-starter`, `file-ingest-spring-boot-starter`, `idempotency-spring-boot-starter`, `messaging-spring-boot-starter`, `notification-service`, `object-storage-spring-boot-starter`, `observability-spring-boot-starter`, `odata-filter-spring-boot-starter`, `pat-spring-boot-starter`, `rest-client-spring-boot-starter`, `security-spring-boot-starter`, `user-settings-spring-boot-starter` |
| `export-spring-boot-starter` | library (starter), parented by the reactor root `common` | `crud-service-example` |
| `crud-service-example` | service, parented by `ludwig-service-parent` | none |
| `notification-service` | service, parented by `ludwig-service-parent` | none |

`web-core-spring-boot-starter`'s sixteen dependents are why the `PageResponse` change is the most
expensive part of this proposal, and why it is one added member rather than a new envelope.

### Shared contracts in `openspec/specs/`

- **`data-access`** - modified, as declared above.
- **`api-evolution`** (from the in-flight `add-api-compatibility-and-release-governance` change) -
  **not modified; it governs how this change is released.** Four of the changes above are
  binary-incompatible, which that spec puts at a **major increment**, which is what this release is.
  Two facts about the enforcement, from that change's own receipt: `revapi` is armed in every module,
  and no `v*` tag exists yet, so every baseline is an empty archive and the compatibility gate
  currently protects nothing. The deletions therefore pass today. **If a `v1.x` tag is published
  before this change ships**, that spec's "An API is removed without ever having been deprecated"
  scenario fails the build for `ODataQueryArgumentResolver` (the baseline would carry it with
  `forRemoval = false`) and for the changed `ODataFilterService.parse` and `ODataQuery.predicate()`
  signatures. The design states the fallback.
- **`problem-detail-pipeline`** - not modified. New rejections (`$count` with a non-boolean value)
  are new codes in this module's existing `ODataFilterProblemMapper` and bundle, which is an instance
  of that pipeline, not a change to it.
- **`i18n-bundles`** - not modified. New message keys go in both existing locale bundles, and the
  existing key-set parity check covers them.
- **`audit-envelope`**, **`long-running-operations`**, **`cache-purpose`**, **`repository-layout`**,
  **`pom-topology`**, **`test-layout`**, **`enforcement-triad`** - not touched. No module is added or
  moved, no POM tier changes, no third-party dependency is added, and the audit question is the
  separate `route-odata-filter-audit-through-audit-core` change.

### Dependencies

**No new in-repo dependency.** `odata-filter-spring-boot-starter` already depends on
`web-core-spring-boot-starter` at compile scope (optional) and already has `querydsl-jpa` and
`jakarta.persistence-api` on its compile path, which is what lets the executor live there rather than in
`db-core`.

**One new optional third-party dependency**, and therefore two POM changes, both in the tier that owns
them: `springdoc-openapi-starter-common` gets a managed entry in `build/ludwig-bom/pom.xml` under the
existing `${springdoc.version}` property, and the module POM declares it `<optional>true</optional>`
alongside `micrometer-core` and `spring-security-core`. It is needed so that the one-parameter controller
signature still produces an OpenAPI document listing all five query parameters - which is the third of
the four objections the README raises against the old argument resolver, and it would otherwise be
inherited. The root POM and `ludwig-service-parent` do not change. Because a POM changes,
`scripts/manifest.sh build` runs before the gate. See design D7.

## Non-goals

- **A second response envelope.** An OData-shaped `{"value": [...], "@odata.count": n,
  "@odata.nextLink": "..."}` was considered and rejected: it is a second paged-response envelope in a
  repository whose rules are explicit that there is one of each mechanism, bought for cosmetic OData
  fidelity. `PageResponse` with an `offset` member is the correct contract and is five lines.
- **`$select` and `$expand`.** Both change the *shape* of the response, which means projections, DTO
  assembly and n+1 management - a different module, not a bigger filter. The README's bounded-surface
  argument stands.
- **`$metadata` / filter discovery.** Separate change: `add-odata-filter-metadata`.
- **Routing the filter audit trail through `audit-core`.** Separate change:
  `route-odata-filter-audit-through-audit-core`.
- **A keyset (cursor) pagination mode.** Offset paging over a total order is what this module
  promises, and `defaultOrderBy` already makes it correct. Keyset paging is a different contract with
  a different envelope and no `$skip`, and proposing it alongside an offset fix would leave the module
  with two.
- **Removing the legacy `ODataFilterExceptionHandler`.** It is not deprecated and it has a live
  purpose - a published starter has consumers who do not use `web-core-spring-boot-starter`. Being a
  major release is not a reason to delete something that still does a job.
- **Generating controllers, services or mappers.** The "golden starter" goal is served by removing
  the boilerplate a service author must write by hand, not by code generation.
