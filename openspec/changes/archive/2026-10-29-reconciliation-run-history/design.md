## Context

The change is confined to `reconciliation-spring-boot-starter`, which has no in-repo dependents. It is designed
here because it changes the shared `reconciliation-admin` contract, adds a table and changes threading for
operator-triggered runs.

Facts this builds on:
- `TaskRunner.run`, `dryRun` and `backfill` all take a `RunLock` lease whose `runId()` already identifies the run
  in `RunContext`, staged inbox rows, `ReconciliationAuditEvent` and `sync_task_state.last_run_id`.
- `RunOutcome` is `SUCCESS`, `FAILURE`, `SKIPPED_LOCKED` or `TIMED_OUT`.
- The admin controller's `finished(...)` builds an envelope by hand.

## Goals / Non-Goals

**Goals:**
- A run is a resource with a real lifecycle.
- An operator request never holds an HTTP thread for a whole pass.
- Scheduled and manual runs are recorded the same way.

**Non-Goals:** see the proposal.

## Decisions

1. **One table, written by `TaskRunner`, not by the admin layer.** `RunRecorder` (engine) inserts the row when the
   lease is acquired, in its own `REQUIRES_NEW` transaction: `PENDING` for a manual run, `RUNNING` for a scheduled
   one. It then moves the row to `RUNNING`, and to terminal in the existing `finally` beside
   `metrics.recordRun`. Recording at the engine is what makes scheduled runs visible. The admin layer only reads.

   If the recorder's write fails, the run is not failed. The failure is logged and metered
   (`ludwig.reconciliation.run.record.failed`). This follows the platform's rule that telemetry must not break the
   pass, and a missing history row is visible as a gap.
2. **Asynchronous manual runs with the lease taken on the request thread.** `TaskRunner` gains `start(task, tier,
   kind, trigger)`:
   - it acquires the lease on the request thread, so `409` stays synchronous and exact;
   - it inserts the `PENDING` row;
   - it submits the pass, which owns the `RunLockHandle`, to `reconciliationManualRunExecutor`;
   - it returns the run id.

   The executor is a `ThreadPoolTaskExecutor` with `core = max = ludwig.reconciliation.runs.manual-concurrency`
   (default 2) and queue capacity 0. A `TaskRejectedException` is handled on the request thread: it deletes the
   `PENDING` row it just inserted, releases the lease, and maps to `429` with `Retry-After: 30`. The row existed
   only for the length of one failed submit.

   Why not the fetch executor: the pass fans out onto it (`BoundedFanOut`), so a run occupying a fetch thread
   would wait for fetch threads, which deadlocks.

   Why not `@Async`: the platform injects its executors explicitly (see `PatUsageTracker`'s javadoc), so tests can
   substitute one.
3. **Envelope from the row.** `RunViews.toOperation(run)` replaces `finished(...)`:
   - `id` is the run id;
   - `status` comes from the row;
   - `result` is `OperationResult.at(<run resource>)` on success;
   - `failure` is `OperationFailure.of(code)` on failure;
   - `detail` is the kind.

   `OperationResponses.accepted(..., Location /runs/{id})` then enforces the envelope contract. This is the module
   edge the `long-running-operations` capability prescribes, not a second envelope.
4. **Counts.** Four columns: `demanded`, `fetched`, `produced` (staged or applied, by mode) and `quarantined`.
   - `demanded` and `fetched` come from the walker, which already counts them for metrics.
   - `produced` is the existing return value.
   - `quarantined` is the delta in quarantine counted by `RecordApplier` for this run id.

   The dry-run report and the backfill result go in a `result_json JSONB` column. They are payload-free by
   construction: keys and counts, as today's response bodies are.
5. **Retention.** `RunPurge extends SelfSchedulingLifecycle` follows `AuditRetentionPurge`: a lock, bounded
   `DELETE ... LIMIT`, and the transaction on the repository method. It is not audited per batch, because
   operational history is not an audit trail; that is the same distinction the outbox README draws for
   `outbox_status_history`.
6. **Paging.** `AdminPage`/`AdminCursor` from `security-spring-boot-starter`, keyset on `(submitted_at, id)`. These
   are the same primitives every area already uses.

**Dependency-direction check.** Does `reconciliation-spring-boot-starter` gain an in-repo dependency? No.
`job-core`, `web-core-spring-boot-starter` and `security-spring-boot-starter` are already declared. No edge is
added, and the module has no in-repo dependents.

**POMs.** No POM changes: not the root `pom.xml`, `ludwig-bom` or `ludwig-service-parent`.

**Mechanical checks.** No new convention. The envelope contract is enforced by `OperationResponses` as before, the
migration by `scripts/check_migrations.sh`, and the operation vocabulary by `RuleGroup.OPERATIONS`. The kind enum
(`SCHEDULED`, `MANUAL`, `DRY_RUN`, `BACKFILL`) has no status words in it.

## Risks / Trade-offs

- [The lease is acquired on one thread and released on another.] → `JdbcRunLock`'s handle is a row, not a
  thread-bound monitor. `JdbcRunLockIT` gains a cross-thread release case to prove it.
- [A replica dies with a `PENDING` or `RUNNING` row.] → Each `RunPurge` pass also runs one `UPDATE`. It marks
  `FAILED`, with code `ludwig.reconciliation.error.run-abandoned`, every non-terminal row whose lease row is gone
  or carries a different run id. Without this the row would stay `RUNNING` forever. Until the next pass, a read
  shows it as it was last written.
- [Clients that read the old synchronous `result` body break.] → **BREAKING**, listed in the CHANGELOG. They poll
  the status header instead.

## Migration Plan

`0015-create-run.sql` creates `sync_run` with indexes on `(task_name, submitted_at DESC, id)` and
`(finished_at) WHERE finished_at IS NOT NULL`, plus a rollback that drops the table. There is no backfill; history
starts at upgrade.
