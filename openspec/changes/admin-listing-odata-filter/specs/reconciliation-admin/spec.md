## MODIFIED Requirements

### Requirement: Every run is a readable resource
Every run that acquired its lease, whether scheduled, manual, dry or backfill, SHALL be recorded with its task,
tier, kind, trigger, instance, correlation id, timestamps, outcome, counts (keys demanded, records fetched, rows
produced) and failure code. A non-terminal run resource SHALL answer with `Retry-After`. Under
`reconciliation.read` the area SHALL serve `GET /admin/v1/reconciliation/tasks/{task}/runs` (newest first,
keyset-paged, filtered with `$filter`) and `GET /admin/v1/reconciliation/tasks/{task}/runs/{runId}`, each
as the platform operation envelope with the run's counts beside it. An unknown task or run SHALL be `404`. A run
that lost the lease race SHALL NOT be recorded.

#### Scenario: A scheduled run failed overnight
- **WHEN** the 02:00 scheduled run of `supplier-sync` threw, and an operator lists that task's runs with
  `$filter=outcome eq 'FAILURE'`
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

## ADDED Requirements

### Requirement: A task's runs are filterable on their outcome and timing
`$filter` on `GET /admin/v1/reconciliation/tasks/{task}/runs` SHALL accept `tier`, `kind`, `outcome`, `triggeredBy`,
`instance`, `failureCode`, `correlationId`, `submittedAt`, `startedAt` and `finishedAt`, within the task named by the
path. The run's result body SHALL NOT be filterable, and the task name SHALL come only from the path.

#### Scenario: Failed manual runs of one task
- **WHEN** an operator lists `supplier-sync`'s runs with `$filter=kind eq 'MANUAL' and outcome eq 'FAILURE'`
- **THEN** only failed manual runs of `supplier-sync` are returned

#### Scenario: Runs still in progress
- **WHEN** an operator lists `$filter=startedAt ne null and finishedAt eq null`
- **THEN** only runs that started and have not finished are returned
