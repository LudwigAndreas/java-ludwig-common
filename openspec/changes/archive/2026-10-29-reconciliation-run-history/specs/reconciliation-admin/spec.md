## MODIFIED Requirements

### Requirement: Safe actions are the operate tier

Under `reconciliation.operate` the area SHALL serve:

| former actuator action | endpoint |
|---|---|
| `run` | `POST /admin/v1/reconciliation/tasks/{task}/runs` with `{tier}` |
| `dry-run` | `POST /admin/v1/reconciliation/tasks/{task}/runs` with `{tier, dryRun: true}` |
| `backfill` | `POST /admin/v1/reconciliation/tasks/{task}/backfills` with `{keys}` |
| `reset-cursor` | `POST /admin/v1/reconciliation/tasks/{task}/cursor/reset` with `{tier}` |
| `requeue-quarantined` | `POST /admin/v1/reconciliation/tasks/{task}/quarantine/requeue` |
| `requeue-job` | `POST /admin/v1/reconciliation/tasks/{task}/remotejobs/{jobId}/requeue` |
| enumeration `discover` | `POST /admin/v1/reconciliation/tasks/{task}/discover` |
| enumeration `requeue-members` | `POST /admin/v1/reconciliation/tasks/{task}/members/requeue` |
| scope `walk` | `POST /admin/v1/reconciliation/tasks/{task}/scopes/{scope}/walk` with `{kind}` |
| scope `preview` | `POST /admin/v1/reconciliation/tasks/{task}/scopes/{scope}/preview` |
| scope `reset-scope` | `POST /admin/v1/reconciliation/tasks/{task}/scopes/{scope}/reset` |
| scope `discard-sweep` | `POST /admin/v1/reconciliation/tasks/{task}/scopes/{scope}/sweep/discard` |

A dry run, a backfill, and a run of a task that is not an enumeration and does not use the asynchronous remote-job
fetch shape SHALL take the task's run lease before answering, SHALL then answer `202` with the run's platform
operation envelope (`id` equal to the run id) and a `Location` header naming
`/admin/v1/reconciliation/tasks/{task}/runs/{runId}`, and SHALL execute the pass after answering. When the module's
manual-run capacity is exhausted the request SHALL be refused with `429` and a `retryAfterSeconds` problem property,
and no lease SHALL be held. A run of an enumeration, or of a task using the asynchronous remote-job fetch shape,
SHALL trigger that task's walk or submit pass synchronously and answer `200` with what the pass did; such a pass is
not a recorded run. A request the task's state does not permit SHALL be `409`. An unknown task, or a scope action on
a task that is not an enumeration, SHALL be `404`.

#### Scenario: A run is started
- **WHEN** an operator starts a run of task `supplier-sync` on tier `hot`
- **THEN** the response is `202` with that run's envelope, `Location` names
  `/admin/v1/reconciliation/tasks/supplier-sync/runs/{runId}` for that envelope's id, and one audit event records
  the request

#### Scenario: A run is requested while one is in progress
- **WHEN** the task is already running on any replica
- **THEN** the response is `409` and no second run starts

#### Scenario: Manual-run capacity is exhausted
- **WHEN** two manual runs are executing and the capacity is two
- **THEN** a third run request on a different task is `429` with a `retryAfterSeconds` property, and that task's
  lease is not held

#### Scenario: An enumeration is run on demand
- **WHEN** an operator runs an enumeration
- **THEN** its walk pass runs before the answer, the response is `200` with the pass and whether it ran, and no run
  is recorded

## ADDED Requirements

### Requirement: Every run is a readable resource
Every run that acquired its lease, whether scheduled, manual, dry or backfill, SHALL be recorded with its task,
tier, kind, trigger, instance, correlation id, timestamps, outcome, counts (keys demanded, records fetched, rows
produced) and failure code. A non-terminal run resource SHALL answer with `Retry-After`. Under
`reconciliation.read` the area SHALL serve `GET /admin/v1/reconciliation/tasks/{task}/runs` (newest first,
keyset-paged, filterable by `tier` and `status`) and `GET /admin/v1/reconciliation/tasks/{task}/runs/{runId}`, each
as the platform operation envelope with the run's counts beside it. An unknown task or run SHALL be `404`. A run
that lost the lease race SHALL NOT be recorded.

#### Scenario: A scheduled run failed overnight
- **WHEN** the 02:00 scheduled run of `supplier-sync` threw, and an operator lists that task's runs with
  `status=FAILED`
- **THEN** the run is listed with kind `SCHEDULED`, its start and finish times, `status: FAILED` and a failure code

#### Scenario: A manual run is polled to completion
- **WHEN** an operator polls the status header of a run they started, until it is terminal
- **THEN** the final envelope is `SUCCEEDED` with `result` naming the run resource, and the counts show how many
  keys were demanded, records fetched and rows produced

#### Scenario: A run that timed out
- **WHEN** a run stops because it reached its `run-timeout`
- **THEN** its envelope is `FAILED` with failure code `ludwig.reconciliation.error.run-timed-out`

#### Scenario: A dry run's report
- **WHEN** a dry run finishes
- **THEN** its run resource's `result` carries the comparison report, and nothing was applied

#### Scenario: The run joins the audit trail
- **WHEN** an operator reads a run and then the task's audit events for the same period
- **THEN** the run's `run.started` and `run.finished` events carry attribute `runId` equal to the run's id and the
  run's correlation id

### Requirement: Run history is kept for a bounded period
Runs that finished longer ago than `ludwig.reconciliation.runs.retention` (default `P30D`) SHALL be deleted by a
registered job named `ludwig-reconciliation-run-purge`, run under its run lock, in bounded batches. The same job
SHALL end, as `FAILED` with code `ludwig.reconciliation.error.run-abandoned`, every unfinished run submitted longer
ago than its task's run timeout plus five minutes. A non-positive retention SHALL fail startup.

#### Scenario: An old run is purged
- **WHEN** a run finished 31 days ago and the purge runs with default settings
- **THEN** `GET .../runs/{runId}` for it is `404`

#### Scenario: A run whose replica died
- **WHEN** a run of a task with a five-minute run timeout was submitted an hour ago and never finished, and the purge
  runs
- **THEN** its envelope is `FAILED` with code `ludwig.reconciliation.error.run-abandoned`
