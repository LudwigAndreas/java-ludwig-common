## Why

`reconciliation-spring-boot-starter` refreshes local rows it already knows the key of. It cannot
perform a first fetch: a record the partner returns that has no local row is settled as `UNCHANGED`
and dropped, its paged shape has one cursor per task so a query cannot be parametrised by a local
row, and nothing notices a record that stopped belonging to a query. Every integration that mirrors
a *collection* - the issues matching a JQL (one query, or one per stored substring), the N records
an external service holds for a local `source`, the aggregates and children under it, the page tree
under a Confluence page - therefore falls outside the starter and gets a hand-rolled loop again,
which is the thing the starter exists to prevent.

## What Changes

- Add a second task kind to `reconciliation-spring-boot-starter`, an **enumeration task**: for each
  local *scope* row that is due, walk a paged partner feed parametrised by that scope, create or
  update a local *member* for each item, and - after a complete full walk - report the members that
  were not seen.
- Scopes are local rows supplied by the integration. A single fixed scope, a scope per stored
  object, and a member that is itself a scope of the same or another task (a tree) are all the same
  mechanism; the engine has no notion of depth.
- Cursor, watermark, walk generation and due time are kept **per scope**, so a scope added today
  gets a full first walk regardless of how far other scopes have advanced, and freshness is
  configured per task as a full-walk interval and an optional incremental interval.
- Membership is recorded in an engine-owned table (one narrow row per scope and member, carrying
  the last walk generation that saw it and the item's payload hash). An unchanged item costs one
  engine write and never reaches the integration's code.
- Removal is reported, not performed: the integration is told a member *left a scope* and decides
  whether that is a soft delete, a hard delete, an unlink or nothing. The starter takes no
  dependency on `db-core`'s `SoftDeleteEntity`.
- A removal sweep runs only after a complete, non-incremental, failure-free walk, is withheld when
  it would remove more than a configured fraction of a scope, and can require several consecutive
  misses for partners whose paging drifts.
- Members do not go through `sync_inbox_record`. A page is applied in one transaction; the next
  walk is the retry.
- Two new tables and their Liquibase changesets, a new `ludwig.reconciliation.enumerations.<name>`
  configuration block with startup validation, metrics, audit events through the existing
  `AuditSink`, and operator actions on the existing `reconciliation` actuator endpoint.
- No change to the existing `SyncTask` contract, to the four `Fetcher` shapes, or to any existing
  table. Nothing is **BREAKING**.

## Non-goals

- A fifth `Fetcher` shape, or any change to `SyncTask`, `DemandProvider.byKey` semantics or
  `RecordApplier`. The key-addressed half stays exactly as it is.
- Tree awareness in the engine: no parent/child model, no cascade, no ordering between levels. A
  tree is one task per level, or one task whose members are also its scopes.
- A consistent snapshot across scopes or levels. Each scope is fresh on its own clock.
- Deciding delete versus flag, or restoring a soft-deleted row. `SoftDeleteEntity`'s documented
  `@SQLRestriction` hides deleted rows from the lookup an upsert would use; closing that gap is a
  `db-core` change of its own and is not part of this one.
- Bulk volumes that need set-based merging. Per-member engine rows are proportional to the mirrored
  data; an integration importing millions of rows a day still belongs in
  `file-ingest-spring-boot-starter`.
- A new SQL carve-out. The membership writes are expressed in QueryDSL and JPA.
- Cluster-wide quotas (`quotas.*`) for enumeration tasks. Rate limits and the named REST client are
  supported; a leased quota counts long-lived remote work, which a page fetch is not.
- Any Jira- or Confluence-specific code. Those are integrations written against this SPI in the
  consuming service.

## Capabilities

### New Capabilities

- `scope-enumeration-sync`: mirroring a partner-side collection under a local scope row - scope
  discovery and scheduling, per-scope paged walks with checkpointing, member create/update,
  membership tracking, removal reporting with its guards, configuration validation, and the
  operator and observability surface.

### Modified Capabilities

None. The change touches no existing requirement in `openspec/specs/`. It is built *on* four
existing contracts without altering them: `run-lock` (per-task run lease), `database-migration`
(changeset shape), `data-access` (QueryDSL only, no new carve-out) and `audit-envelope` (the single
`AuditSink`).

## Impact

- **Module:** `reconciliation-spring-boot-starter` - library tier, parented by the reactor root
  `common`, imports `ludwig-bom`. It is the only module touched.
- **In-repo dependents of that module:** none (`scripts/manifest.sh module
  sources/reconciliation-spring-boot-starter` reports "depended on by (0)"), so the gate is
  `mvn -q validate` and `mvn -pl :reconciliation-spring-boot-starter -am verify`.
- **POMs:** no POM changes, no new dependency, no new third-party version.
- **Schema:** two new tables, `sync_scope_state` and `sync_scope_member`, added as changesets
  `0012` onward in the module's own changelog. No existing table is altered.
- **Public API:** new types in `ru.ludwigandreas.reconciliation.api`; new configuration keys under
  `ludwig.reconciliation.enumerations`; new actions on `POST /actuator/reconciliation/{task}`.
  Existing types, keys and actions are unchanged.
- **Documentation:** `README.md` and `README.ru.md` of the module gain an enumeration section, and
  the "what this module is not for" section is narrowed to files and bulk volumes.
