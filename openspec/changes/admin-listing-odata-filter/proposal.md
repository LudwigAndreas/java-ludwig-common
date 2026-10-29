## Why

The platform's business APIs filter with OData `$filter`, but its administrative listings each take a handful of
hand-picked query parameters (`?status=`, `?actor=`, `?via=`). An operator cannot ask a question the parameters did
not foresee, such as "denied PAT operations this week" or "dead-lettered messages of one event type created before
Monday". Every new filter is a code change in a starter, and an administrative UI has to learn two query styles.
The OData starter already turns `$filter` into a guarded QueryDSL predicate. The paged administrative listings are
QueryDSL over JPA entities, so they can accept it without giving up their keyset pagination.

## What Changes

- **`$filter` on every paged administrative listing over stored rows.** Six listings accept an OData `$filter`
  expression:
  - `GET /admin/v1/audit/events`;
  - `GET /admin/v1/outbox/messages`;
  - the reconciliation run history `GET /admin/v1/reconciliation/tasks/{task}/runs`;
  - the export runs listing;
  - the file-action submissions listing;
  - the PAT listing.
- **Filter only; paging stays keyset.** The listing keeps its fixed newest-first order and its `cursor`/`size`
  paging. `$orderby`, `$top`, `$skip` and `$count` on an administrative listing are refused with `400`. Offset
  paging over large, fast-changing tables both slows down page by page and skips or repeats rows.
- **BREAKING: `$filter` replaces the explicit filter parameters.** These are removed:
  - the audit search's `category`, `action`, `actor`, `via`, `resourceType`, `resourceId`, `outcome`, `from` and
    `to`;
  - the outbox's `status`, `eventType`, `destination` and `createdBefore`;
  - the run history's `tier` and `status`;
  - the export runs' `requester`, `report` and `status`;
  - the submissions' `action`, `submitter` and `status`;
  - the PAT listing's `owner` and `status`.

  There is one query style per listing. Filters on a status now name the stored field and its stored values, for
  example `outcome eq 'FAILURE'` on a run or `revokedAt eq null` on a token, rather than the envelope status the
  parameters mapped from. Path segments that name what is listed (`{task}`) stay. The export subscriptions listing
  is not paged and keeps its parameters.
- **A filter can only narrow.** `$filter` is ANDed under every server-side rule:
  - tenant confinement of a `USER` caller;
  - the audit search's default 30-day window, which applies unless the filter constrains `occurredAt`;
  - the reconciliation `{task}` path and object scope.
- **Deny-by-default fields.** Each entity marks with `@Filterable` the fields an operator may filter on. Free text
  and secrets are never filterable:
  - payloads and error texts;
  - audit attributes and reasons;
  - PAT hashes.

  An unknown or unfilterable field is `400`, with the starter's existing problem types.
- **Audit.** An administrative search's own audit event records the filter's paths and operators, never its values,
  as the OData starter already does for business APIs.
- **Without the OData starter** in a service, a `$filter` on an administrative listing is refused with `400`
  (`ludwig.admin.filter-unavailable`). Every other parameter works as today.

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `admin-authorization`: adds the rule every paged administrative listing over stored rows follows. It accepts
  `$filter`, refuses the other query options, and confinement is ANDed underneath.
- `audit-trail-search`: searching by `$filter` only, with its filterable fields; the `via` parameter requirement is
  removed.
- `outbox-admin`: the messages listing filters by `$filter` only, with its filterable fields.
- `reconciliation-admin`: the run history filters by `$filter` only, with its filterable fields.
- `export-admin`: the runs listing filters by `$filter` only, with its filterable fields.
- `file-action-admin`: the submissions listing filters by `$filter` only, with its filterable fields.
- `personal-access-token`: the administrative token listing filters by `$filter` only, with its filterable fields.

## Non-goals

- `$orderby`, `$top`, `$skip`, `$count` on administrative listings, and offset paging in general.
- A cursor (keyset) mode in the OData starter for business APIs.
- Filtering listings that are not stored rows: approvals and run locks (plain JDBC), database-log consumer positions,
  the jobs list and reconciliation's task registry. These keep their explicit parameters.
- Filtering inside JSON columns (audit `attributes`, outbox `payload`).
- The identity grants list, which is not paged.

## Impact

- Modules (all libraries, POM parent `common`) and their in-repo dependents. The gate runs the union:
  - `db-core` gains an optional dependency on `odata-filter-spring-boot-starter` and the neutral
    `AdminQueryFilter` adapter. Its 12 dependents are the six adopters plus `crud-service-example`,
    `file-ingest-spring-boot-starter`, `idempotency-spring-boot-starter`, `identity-projection-spring-boot-starter`,
    `notification-service` and `user-settings-spring-boot-starter`.
  - `audit-spring-boot-starter`: 1 dependent, `user-settings-spring-boot-starter`.
  - `outbox-spring-boot-starter`: 9 dependents.
  - `reconciliation-spring-boot-starter`: no dependents.
  - `export-spring-boot-starter`: 1 dependent, `crud-service-example`. It already depends on the OData starter.
  - `file-action-spring-boot-starter`: 1 dependent, `crud-service-example`.
  - `pat-spring-boot-starter`: no dependents.

  The five adopters other than export gain an optional dependency on `odata-filter-spring-boot-starter` for the
  `@Filterable` annotations. `odata-filter-spring-boot-starter` itself is unchanged; its dependents are
  `crud-service-example`, `export-spring-boot-starter`, `notification-service` and
  `user-settings-spring-boot-starter`.
- **POM changes**:
  - `db-core` and five starters add an optional dependency;
  - no change to the root `pom.xml`, `ludwig-bom` or `ludwig-service-parent`;
  - the manifest is regenerated.
- Shared contracts: the seven capabilities above are modified. `odata-query-contract` and `odata-filter-metadata`
  are read but not changed.
- Both services' OpenAPI documents gain `$filter` and lose the removed parameters on the six listings.
- READMEs (EN and RU) of the six adopters and of `db-core`, the admin guide, and `CHANGELOG` (EN and RU).
