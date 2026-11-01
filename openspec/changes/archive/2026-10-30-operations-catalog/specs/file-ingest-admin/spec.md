## MODIFIED Requirements

### Requirement: Tasks and runs are readable

`file-ingest-spring-boot-starter` SHALL declare `fileingest.task.read` (`READ`) and `fileingest.run.start`
(`CHANGE`) on the `ADMIN` surface, and serve under `fileingest.task.read`:

- `GET /admin/v1/fileingest/tasks` - each task with its recent runs;
- `GET /admin/v1/fileingest/tasks/{task}` - one task;
- `GET /admin/v1/fileingest/tasks/{task}/runs/{runId}` - one run: the platform operation envelope, the checkpoint,
  the counts recorded on the run, and up to 20 quarantine rows' coordinates and reasons, without row
  contents.

#### Scenario: Is the run moving
- **WHEN** an operator reads a run that is `RUNNING`
- **THEN** the response carries its checkpoint and counts alongside the envelope

### Requirement: A run can be triggered

`POST /admin/v1/fileingest/tasks/{task}/runs` (`fileingest.run.start`) SHALL start a pass and answer `202` with the
operation envelope and a status header naming the new run. A task already running SHALL be `409`.
`ludwig.ingest.endpoint.allow-manual-run` SHALL no longer exist; the grant decides.

#### Scenario: An operator triggers a run
- **WHEN** a caller holding `fileingest.run.start` triggers task `price-lists`
- **THEN** the response is `202`, the status header names `/admin/v1/fileingest/tasks/price-lists/runs/{runId}`, and an
  audit event records it
