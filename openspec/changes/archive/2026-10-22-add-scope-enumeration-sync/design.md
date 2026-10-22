## Context

See `proposal.md` for motivation. The constraints that shape the approach, all read from the code:

- `RecordApplier.decide` settles a staged record as `UNCHANGED` when `DemandProvider.byKey` is
  empty, on the stated assumption that the local row was deleted between fetch and apply. That is
  correct for a key-addressed task and is exactly what drops a first-fetched record.
- `Fetcher.Paged.fetchPage(cursor, PageRequest)` takes no scope, and `sync_task_state` holds one
  cursor and one watermark per task and tier.
- `Fetcher` is sealed over four shapes, and `SyncTask` is built around a correlation key that
  addresses an existing local row. The README states both as deliberate.
- `StagingService` writes one `sync_inbox_record` row per `Found` outcome with no grouping by key;
  the applier's hash and stale-stamp guards are per key.
- The module already owns everything operational that an enumeration needs: `job-core`'s `RunLock`
  and `ScheduledJob`, `RateLimitRegistry`, `PayloadCodec.hashJson`, `KeyCodec`, `PageResult`,
  `BackoffCalculator`, `ReconciliationMetrics`, the `AuditSink` wiring and the actuator endpoint.

The change is confined to `reconciliation-spring-boot-starter`. A design is written anyway because
it adds a persisted data model and a second public SPI to a published starter.

## Goals / Non-Goals

**Goals:**

- One mechanism that covers a single fixed query, a query per local row, a parent with N records,
  a multi-level tree and a self-similar tree, without the engine knowing which it is serving.
- Correct first fetch for a scope added at any time.
- Removal that cannot be triggered by an interrupted walk, an incremental walk or a partner outage.
- No behavioural change for any existing `SyncTask`.

**Non-Goals:**

- Sharing code paths with the staged apply pass. The two task kinds share infrastructure, not the
  record lifecycle.
- Making sizing decisions for the integration. Every walk is paged and checkpointed; a small scope
  is a one-page walk.

## Decisions

### D1. A second task kind, not a fifth `Fetcher` shape

`EnumerationTask` sits beside `SyncTask` in `ru.ludwigandreas.reconciliation.api`, with its own
bean annotation and its own configuration block, `ludwig.reconciliation.enumerations.<name>`.

*Why:* `Fetcher` means "external state for a set of known keys" and `byKey` empty means "deleted
locally". Enumeration contradicts both. Bolting it onto `Paged` would make `byKey` empty mean two
opposite things depending on a flag.

*Alternative considered:* a new starter on `job-core`. Rejected: it would duplicate the rate-limit
registry, the validator, the actuator endpoint and the metrics conventions, and an operator would
have two places to look for one partner.

Task names are unique across both kinds, because the run-lock name
`reconciliation:<name>:<pass>` and the actuator path `/actuator/reconciliation/{task}` are shared.

### D2. The SPI

```java
public interface EnumerationTask<S, SK, M, MK> {
    String name();
    ScopeSource<S, SK> scopes();            // page(afterKey, limit), byKey(key), keyOf(scope)
    KeyCodec<SK> scopeKeyCodec();
    MemberFeed<S, M> feed();                // fetchPage(scope, cursor, FeedRequest) -> PageResult<M>
                                            // supportsChangedSince()
    Function<M, MK> memberKey();
    KeyCodec<MK> memberKeyCodec();
    MemberReconciler<S, M, MK> reconciler(); // apply(scope, item, context) -> MemberResult
                                            // left(scope, memberKey, context) -> LeftResult
    default ExternalStamp stampOf(M item) { return ExternalStamp.none(); }
}
```

- `ScopeSource.page` is keyset-paged by encoded scope key so discovery never loads every scope at
  once. `ScopeSource.single(key)` covers the fixed-scope case with no local row.
- `MemberResult` is `applied | unchanged | skipped`. `skipped` is how a filter the partner cannot
  apply is expressed; the engine keeps no membership row for a skipped item.
- `MemberReconciler.left` defaults to "kept", mirroring `Reconciler.reconcileMissing`: doing
  nothing on the strength of an absence is the safe default, and it is counted in a metric so it is
  not silent.
- `PageResult`, `KeyCodec` and `ExternalStamp` are reused as they are.

*A tree* is not modelled. Task A's `apply` saves aggregates; task B's `ScopeSource` queries them.
A self-similar tree is one task whose `ScopeSource` queries the rows its own `apply` writes.

### D3. Two tables, engine-owned

```
sync_scope_state                              sync_scope_member
  task_name, scope_key          UNIQUE          task_name, scope_key, member_key   UNIQUE
  generation            BIGINT                  seen_generation   BIGINT
  walk_kind             FULL | INCREMENTAL      payload_hash      VARCHAR
  walk_cursor, walk_started_at                  status            ACTIVE | FAILED | QUARANTINED
  walk_attempts, last_error                     attempts, next_attempt_at, last_error
  watermark, high_water                         first_seen_at
  last_full_at, last_incremental_at
  next_due_at
  sweep_held_at, sweep_held_count
  version
```

*Why engine-owned and not a marker column on the integration's table:* stamping "seen" on a domain
row churns its optimistic-lock version, `updated_at` and audit on every full walk; the engine could
not compute the unseen set without knowing the schema; and the payload hash is what lets an
unchanged item skip the integration entirely, which replaces what the inbox did for key-addressed
tasks.

*Why a generation counter and not a timestamp:* walks span runs and instances, and "seen before
the walk started" by wall clock is wrong under skew.

*Alternative considered:* an in-memory seen-set. Rejected: a walk is resumable across restarts and
the set is not.

Cost, stated plainly: one narrow row per scope and member, proportional to the mirrored data and
not to history.

### D4. Two passes per task: discover and walk

- **discover** pages through `ScopeSource`, inserts a `sync_scope_state` row (due now) for any
  scope key not yet known. It never deletes.
- **walk** selects due scopes from `sync_scope_state` ordered never-completed first, then by
  `next_due_at`, up to `walk.max-scopes-per-run`, and walks them under `walk.max-concurrency`.

A scope whose local row has gone is detected lazily: `ScopeSource.byKey` is empty at walk time, and
the engine deletes its state and membership rows without calling `left`. The integration deleted
the scope; what happens to its members is that integration's cascade.

*Alternative considered:* asking the integration for "due scopes" each run, as `DemandProvider`
does. Rejected because due-ness depends on per-scope state the engine holds, and that is the
feedback gap that makes per-record freshness awkward for `SyncTask` today.

Both passes take the task's run lease through `RunLock`, so a scope is never walked by two
instances.

### D5. Choosing the walk kind

A walk is **full** when the scope has never completed one, when `refresh.full-interval` has elapsed
since `last_full_at`, or when the feed does not support changed-since. Otherwise it is
**incremental**, bounded by the scope's `watermark`.

`generation` counts completed full walks. A full walk in progress stamps the members it sees with
`generation + 1`, and the counter moves only when that walk completes, so an abandoned walk is
never counted as a miss. A walk with a persisted cursor is resumed with its original kind. `watermark` is set from `high_water` only when a
walk completes, so an interrupted walk cannot move it past pages it never read.

### D6. A page is the transaction; an item is the fallback

Per page, in one transaction: load the membership rows for the page's keys in one query; mark the
unchanged ones seen with one bulk update; call `apply` for new and changed items; insert or update
their membership rows; write the cursor. The integration's own writes join this transaction, so
the cursor, the membership and the domain rows commit together.

When a page fails, it is replayed item by item, each in its own transaction. Items that fail are
recorded on their membership row (`FAILED`, attempts, backoff, `QUARANTINED` at the budget) and the
walk is marked not clean. The walk continues.

*Why no inbox row per member:* the inbox exists so a retry does not re-call the partner for a key.
In an enumeration the next walk re-reads the feed anyway, so per-member staging would cost a row
per member per walk for nothing.

*Alternative considered:* one transaction per item always. Rejected: it multiplies the write cost
of the common, all-good page by its size.

### D7. The sweep and its three guards

On completion of a full walk, with `generation` already advanced, members of the scope with
`seen_generation <= generation - sweep.confirm-after-misses` are the candidates.

1. **Evidence.** Sweep only when the walk was full, reached its last page and is clean - clean
   meaning the scope has no member in `FAILED` or `QUARANTINED` when the walk completes.
2. **Plausibility.** If the candidates exceed `sweep.max-fraction` of the scope's members, set
   `sweep_held_at` and stop. A partner answering 200 with an empty list during an outage or after a
   permissions change is otherwise indistinguishable from "everything was deleted".
3. **Drift.** `sweep.confirm-after-misses` (default 1) lets an integration over offset paging
   require two consecutive misses.

Each candidate gets `left(scope, memberKey, context)` in its own transaction, then its membership
row is deleted. A member that comes back therefore has no row and reaches `apply` flagged as new,
which is where an integration restores a soft-deleted row. The callback is phrased as leaving a
scope, not as deletion, because one member can belong to several scopes.

### D8. Writes stay in QueryDSL and JPA

The per-page write is a QueryDSL bulk `update` for seen rows plus JPA inserts for new ones. The
natural single statement is `INSERT ... ON CONFLICT`, which QueryDSL-JPA cannot express; taking it
would be a third SQL carve-out. Not taken. If measurement later shows the two-statement form is the
bottleneck, a carve-out is its own change with its own `SqlConfinementTest`.

### D9. Configuration

```yaml
ludwig:
  reconciliation:
    enumerations:
      <name>:
        enabled: true
        schedule:  { fixed-delay: 1m, run-timeout: 15m }
        discovery: { fixed-delay: 5m, page-size: 1000 }
        refresh:   { full-interval: 6h, incremental-interval: 5m }   # incremental is optional
        walk:      { page-size: 100, max-concurrency: 2, max-scopes-per-run: 50 }
        sweep:     { confirm-after-misses: 1, max-fraction: 0.5 }
        retry:     { ... }            # same shape and defaults as a SyncTask
        rate-limit: <name>
        rest-client: <name>
```

`enumeration-defaults` merges per field, the way `TaskSettingsResolver` does for tasks. A sibling
`EnumerationConfigurationValidator` covers the cases in the spec's validation requirement and
reports every problem in one pass; it is a separate class so that the existing validator's public
constructor does not change. For the same reason the passes are owned by a sibling
`EnumerationRuntime` rather than by new constructor parameters on `ReconciliationRuntime`.

### D10. Operations surface

- Actuator, on the existing `reconciliation` endpoint id. Task-level actions join the existing
  `POST /actuator/reconciliation/{task}`: `run`, `discover`, `requeue-members`. Scope-level actions
  are new operations with a second selector, `GET` and `POST /actuator/reconciliation/{task}/{scope}`:
  `walk` (`kind`), `preview`, `reset-scope`, `discard-sweep` and `confirm-sweep` (`confirm`).
  `confirm-sweep` is destructive and follows the existing two-key rule. The scope is a path segment
  rather than a new parameter on the existing `operate` method because a changed method signature
  is a breaking change to revapi under a minor version, and an added operation is not.
- Metrics, tagged `task`: `reconciliation.scope.freshness.lag` (gauge, pulled),
  `reconciliation.scope.walk` (timer, tagged `kind`, `outcome`), `reconciliation.members.settled`
  (counter, tagged `outcome`), `reconciliation.members.quarantined` (gauge),
  `reconciliation.sweep.held` (gauge), `reconciliation.members.left_unhandled` (counter).
- Audit through `AuditSink` using `ReconciliationAuditEvent`, with a new `Category.SCOPE`. No
  `try`/`catch` around `record`.

### Dependency-direction check

Does this change add an in-repo dependency? No. `reconciliation-spring-boot-starter` already
depends on `audit-core`, `db-core`, `job-core`, `observability-spring-boot-starter` and
`rest-client-spring-boot-starter`, and everything used here comes from those. `project-index.json`
`inRepoDependents` for `reconciliation-spring-boot-starter` is `[]`, so nothing can depend back on
it and no cycle is possible.

### POM changes

No POM changes: not the root `pom.xml`, not `ludwig-bom`, not `ludwig-service-parent`.

### New conventions and their checks

| Convention | Check | Owner |
|---|---|---|
| The enumeration SPI depends on no engine class and carries no Spring wiring | extend the module's existing ArchUnit test for the `api` package | `architecture-rules` style, in-module ArchUnit |
| Enumeration code does not reference `SoftDeleteEntity` | new in-module ArchUnit rule | ArchUnit |
| `Fetcher` stays four shapes | unit test on `Fetcher.class.getPermittedSubclasses()` | in-module test; ArchUnit cannot count permitted subclasses |
| Enumeration never writes `sync_inbox_record` | integration test asserting the table is empty for the task | behaviour, not structure - no static check is possible |
| Sweep only on a clean, complete, full walk | integration tests per guard | behaviour - no static check is possible |
| Changeset shape | `scripts/check_migrations.sh`, run by the gate | existing |
| No SQL strings | the platform's existing QueryDSL-only enforcement; nothing new | existing |

### Nothing centralised is reimplemented

Audit goes through `AuditSink`. There is no REST surface, so the operation envelope and the
ProblemDetail pipeline are not involved. Nothing is cached. The membership `status` values
(`ACTIVE`, `FAILED`, `QUARANTINED`) are not a restatement of `OperationStatus`.

## Risks / Trade-offs

- [A walk writes every member's row on every full walk] → one bulk update per page on a narrow
  engine table with no audit or triggers; `refresh.full-interval` is the knob.
- [A poison member stops its scope from ever sweeping] → it is quarantined, counted in
  `reconciliation.members.quarantined` and requeueable; the runbook says so.
- [A held sweep is ignored and stale members accumulate] → `reconciliation.sweep.held` is a gauge
  meant to be alerted on, and the scope keeps walking and applying while it is held.
- [The same member in two scopes is applied concurrently] → the integration's optimistic lock
  fails one of them, which the item-level fallback retries.
- [A move between scopes deletes and re-creates a row when the integration hard-deletes] → by
  design the integration's choice; the README states that a flag preserves identity and that
  `SoftDeleteEntity` needs a find-including-deleted path first.
- [Discovery re-reads all scope keys every `discovery.fixed-delay`] → keyset-paged local reads on
  their own cadence, independent of the walk cadence.
- [Abstraction drawn from five examples and no production integration] → the integration tests
  model three of them (single scope, scope per row, self-similar tree) against a stub partner.

## Migration Plan

Additive. Changesets `0012`-`0014` create two tables and one index; rollback drops them. A
deployment that declares no enumeration task schedules nothing and writes nothing to the new
tables. No existing row, key or action changes meaning.

## Open Questions

- Should `discovery` be skippable for a task that only ever uses `ScopeSource.single`? It can be
  special-cased later without changing the spec.
- `TaskRunner.execute` calls `completeRun` with the high-water mark after a paged walk even when
  the walk stopped at the per-run cap. Whether that can move an existing `SyncTask`'s watermark
  past unread pages was not verified here and is outside this change; it is recorded as something
  to check, because D5 deliberately does not copy it.
