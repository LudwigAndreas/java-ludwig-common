## Why

A reconciliation run leaves no record an operator can query. The task row keeps only `last_run_at` and
`last_run_id`, and the outcome goes to a metric and the audit trail. The administrative surface works around this
(`openspec/intents/admin-surface-follow-ups.md` §2), and the workaround is wrong in three ways:

- `POST /admin/v1/reconciliation/tasks/{task}/runs` holds the HTTP request open for the whole pass, up to the
  task's `run-timeout`.
- It answers with a fabricated envelope: a random id, always `SUCCEEDED`, even when the run threw or timed out.
- It points the status header at the task, which cannot say how that run ended.

"Did last night's 02:00 run of `supplier-sync` fail, and how many records did it quarantine?" has no answer
without SQL over the audit table.

## What Changes

- A `sync_run` table (migration `0015`) holds one row per run:
  - task, tier and kind (`SCHEDULED`, `MANUAL`, `DRY_RUN`, `BACKFILL`);
  - who triggered it, the instance, and the correlation id;
  - submitted, started and finished times;
  - an `OperationStatus`;
  - counts (demanded, fetched, staged or applied, quarantined);
  - a failure code.

  The id is the run lease's `runId`, which the audit events and staged rows already carry, so they join to it.
  A run that loses the lock race is not recorded; it stays a metric, as today.
- New read endpoints under `reconciliation.read`:
  - `GET /admin/v1/reconciliation/tasks/{task}/runs?tier=&status=&cursor=`, newest first and keyset-paged;
  - `GET /admin/v1/reconciliation/tasks/{task}/runs/{runId}`.
- **BREAKING**: manual runs, dry runs and backfills become asynchronous.
  - The request answers `202` with the run's real envelope (`PENDING`), and the status header points to
    `/runs/{runId}`.
  - The run executes on a small dedicated executor (default 2 concurrent manual runs). It is not the fetch
    executor, because a run fans out onto that one and would deadlock waiting on itself. When the executor is
    full, the request is refused with `429` and `Retry-After` before anything starts.
  - A conflict on the run lease is still `409`, decided before the answer: the lease is taken synchronously, then
    handed to the worker.
  - The dry-run report and the backfill's staged count move into the run's `result`.
- Each terminal run maps `RunOutcome` onto `OperationStatus`:

  | `RunOutcome` | `OperationStatus` | failure code |
  |---|---|---|
  | `SUCCESS` | `SUCCEEDED` | none |
  | `FAILURE` | `FAILED` | the exception class's code |
  | `TIMED_OUT` | `FAILED` | `ludwig.reconciliation.error.run-timed-out` |
- Retention: `ludwig.reconciliation.runs.retention` (default `P30D`) is purged in bounded batches by a run-locked
  `job-core` job, `ludwig-reconciliation-run-purge`, the platform's existing purge shape.

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `reconciliation-admin`: runs become resources. The run and backfill requirement changes from synchronous to
  asynchronous, with a per-run status resource.

## Non-goals

- Run history for enumeration walks (`EnumerationRunner`). It has its own scope-state rows; it is a candidate
  follow-up once this shape has proved itself.
- Cancelling a running reconciliation run.
- Recording per-record outcomes in the run row. Quarantine and staging already hold those.
- Moving the scheduled run off the scheduler thread. Only operator-triggered runs change threads.

## Impact

- Module: `reconciliation-spring-boot-starter` (library; parent `common`). No in-repo dependents.
- Shared contract: `reconciliation-admin` is modified. `long-running-operations` is used as it is: `CANCELLED` is
  never produced, and `EXPIRED` is not used.
- Migration `0015-create-run.sql`.
- No POM changes. `job-core`, `web-core` and `security` are already dependencies.
- README (EN and RU): Administration section, configuration table, runbook. `CHANGELOG` (EN and RU).
