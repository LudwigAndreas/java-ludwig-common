## Context

See proposal.md for the motivation. Facts the approach rests on:

- **The six listings.** Each paged administrative listing builds a QueryDSL `Predicate` over one JPA entity and pages
  by keyset through `AdminPage`/`AdminCursor`:
  - `AuditEventQueryRepositoryImpl.predicateFor`;
  - `OutboxAdminQueries.page`;
  - `SyncRunRepositoryCustomImpl`;
  - `ExportAdminQueries.runs`;
  - `FileActionAdminQueries.submissions`;
  - `PatQueryRepositoryImpl`.
- **What the OData starter offers.** `ODataFilterService.parse(entityType, ODataQueryOptions.filterOnly(raw))`
  returns that predicate. It already applies the deny-by-default `@Filterable` allow-list, the depth and length
  limits, and the starter's problem types. It also records the filter's paths and operators, never its values, to
  the audit sink.
- **What it would not do for us.** It pages by offset (`odata-query-contract`). Its metadata discovery publishes an
  entity only when `@FilterPolicy` names one, so an annotated administrative entity stays unpublished.
- **Who depends on it today.** `export-spring-boot-starter` already depends on the starter, optionally. The other
  five adopters do not.

## Goals / Non-Goals

**Goals:**
- `$filter` on the six listings, through the one parser the platform already has.
- Keyset paging, confinement and the existing parameters unchanged.
- A service without the OData starter keeps working.

**Non-Goals:**
- The OData starter's behaviour for business APIs.
- A generic administrative query language beyond `$filter`.

## Decisions

1. **Filter only, keyset kept.** An administrative listing applies `$filter` as one more predicate and keeps its fixed
   order and cursor.
   - `$orderby` is refused, because a client-chosen order would have to be encoded in the cursor, which is a
     second paging model.
   - `$skip` and `$top` are refused, because offset paging over the audit trail, the outbox or run history slows
     down page by page and skips or repeats rows as rows arrive.
   - `$count` is refused, because a count over an unbounded table is the query that takes a service down.

   Alternative rejected: a cursor mode in the OData starter. It is a larger change to a shared contract, for a need
   only the administrative surface has.
2. **The other query options are refused in one place: `AdminSurfaceGuard`.** A request under `/admin/**` that
   carries `$orderby`, `$top`, `$skip` or `$count` is answered `400` with `InvalidAdminRequestException`. No
   administrative endpoint supports them, so the guard does not need to know which handler lists. The check is
   string names only, so the security starter gains no OData dependency.
3. **`AdminQueryFilter` in `db-core`, a neutral adapter.** Its single method is
   `Optional<Predicate> parse(Class<?> entity, String rawFilter)`, with no OData types in the signature.
   - Every adopter already depends on `db-core`, which already carries QueryDSL.
   - `db-core` gains an optional dependency on the OData starter. A nested auto-configuration under
     `@ConditionalOnClass(ODataFilterService)` registers the implementation that delegates to the service.
   - Otherwise the registered implementation answers an empty filter with empty and refuses a non-empty one with
     `400` (`ludwig.admin.filter-unavailable`).
   - The administrative services take `AdminQueryFilter`, so none of them references an OData class, and a
     service without the starter loads every class it has.

   Alternatives rejected:
   - *Six per-module adapters.* They duplicate the same lambda and nested configuration six times.
   - *A non-optional dependency.* It would pull Olingo into every service that uses the outbox or the audit store.
4. **Allow-lists are `@Filterable` on the entity fields the specs name.**
   - The five adopters other than export gain an optional dependency on the OData starter for the annotations.
     Annotations whose class is absent at runtime are ignored by the JVM.
   - The fields excluded by the specs are left unannotated: free text, secrets and personal data. A test in each
     area asserts that one excluded field is refused.
   - `@FilterPolicy` sets `maxDepth` and omits the published name, so no administrative entity appears in the
     metadata discovery.
5. **Composition, and the parameters removed.** Each listing's repository method takes the parsed `$filter` predicate
   in place of its filter record or parameters (`AuditTrailQuery`'s filter fields, `OutboxAdminQueries.Filter`,
   `MessageFilter`, the `(requester, definitionKey, status)` arguments and so on). It ANDs the predicate after the
   server-side rules that remain: tenant confinement, the path's `{task}` and the keyset position. So a filter can
   only narrow the result. The audit search's 30-day default applies when the parsed filter's `FilterSummary` names
   no `occurredAt` path. Each status parameter that mapped an envelope status to stored states goes with the
   parameters, because `$filter` reads stored fields. The user asked for no compatibility, so nothing is
   deprecated, and revapi entries record the removed signatures.
6. **Audit.** Two records are kept for one filtered search, on purpose:
   - the search's own administrative audit event adds a `filter` attribute holding the parsed `FilterSummary`
     (paths and operators), so it stays readable alongside the other facts of that event;
   - the OData starter's own filter record, in the starter's category, is kept unchanged.
7. **Controllers.** Each of the six listing handlers drops its filter parameters and adds
   `@RequestParam(name = "$filter", required = false)`, which it passes to its service. A removed parameter sent by
   an old client is ignored, as Spring ignores any undeclared parameter. The published OpenAPI documents gain the parameter, regenerated with
   `scripts/api-docs.sh`.

**Dependency-direction check.**
- *Does `odata-filter-spring-boot-starter` already depend on `db-core`, directly or transitively?* Its compile
  closure in `project-index.json` is `audit-core` and `web-core-spring-boot-starter`. Neither is `db-core`, and
  neither depends on it. So the new edge `db-core` → `odata-filter-spring-boot-starter` creates no cycle.
- *Does it depend on any adopter?* That closure contains none of `audit-spring-boot-starter`,
  `outbox-spring-boot-starter`, `reconciliation-spring-boot-starter`, `file-action-spring-boot-starter` or
  `pat-spring-boot-starter`. So the five new adopter → starter edges create no cycle either.
- `export-spring-boot-starter` already has the edge.

**POMs.**
- The module POMs of `db-core`, `audit-spring-boot-starter`, `outbox-spring-boot-starter`,
  `reconciliation-spring-boot-starter`, `file-action-spring-boot-starter` and `pat-spring-boot-starter` each add one
  optional dependency. The version comes from the reactor.
- The root `pom.xml` does not change, except for revapi accepted-difference entries for the repository and service
  method signatures that gain a parameter. That is analysis configuration, not build structure.
- `ludwig-bom` and `ludwig-service-parent` do not change.
- The manifest is regenerated.

**Mechanical checks.**
- *Refusal of the offset options.* Enforced at runtime by the guard itself (decision 2) and covered by a test in
  `AdminSurfaceMvcTest`. It needs no static rule, because it is in one place.
- *Each paged administrative listing over JPA accepts `$filter`.* Not mechanically checkable without a semantic
  notion of "listing over stored rows". It is reviewed against the specs' list, and each area's test asserts it.
- *Excluded fields stay unannotated.* No static check can tell free text from a code, so it is covered by the
  per-area refusal tests.

## Risks / Trade-offs

- [A filter on an unindexed column, for example `aggregateId` on a large outbox, is slow.] → `maxDepth` and the
  length limit bound the expression but not its cost. Every listing keeps its page-size cap and, for the audit trail,
  its 30-day default window. The READMEs name the indexed fields as the ones to filter on first.
- [An old client still sends `?status=FAILED` and silently gets an unfiltered page.] → This is accepted, because
  compatibility is explicitly not required. The CHANGELOG lists every removed parameter with its `$filter`
  equivalent, and listings are read-only, so an unfiltered page changes nothing.
- [Status filters change meaning: stored values (`FAILURE`, `revokedAt eq null`) instead of envelope statuses.] → The
  stored values are what the rows hold. Each README lists each listing's filterable fields and their values.
- [The OData starter's problem types on the administrative surface.] → They are the same types business APIs return,
  rendered by the one problem pipeline. An administrative UI handles one more `400` shape that it already handles
  elsewhere.
