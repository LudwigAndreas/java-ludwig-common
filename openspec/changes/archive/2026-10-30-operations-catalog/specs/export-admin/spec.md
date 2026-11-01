## MODIFIED Requirements

### Requirement: Every requester's runs and subscriptions are readable

`export-spring-boot-starter` SHALL declare, on the `ADMIN` surface, `export.run.read` and `export.subscription.read`
(`READ`) and `export.run.cancel` (`CHANGE`), and serve:

- `GET /admin/v1/exports/runs?$filter=&cursor=` and `GET /admin/v1/exports/runs/{runId}` (`export.run.read`) - each run in the platform
  operation envelope with its requester and report, without its output;
- `GET /admin/v1/exports/subscriptions?owner=&report=` (`export.subscription.read`) - each subscription with its schedule, run-as identity and
  last run.

The requester-scoped endpoints SHALL keep answering `404` to a caller who is not the requester; administrators use
this area instead.

#### Scenario: An administrator finds a stuck run of another user
- **WHEN** a caller holding `export.run.read` lists runs with `$filter=status eq 'RUNNING'`
- **THEN** runs of every requester are listed, and no response offers the run's output

### Requirement: Any run can be cancelled

`POST /admin/v1/exports/runs/{runId}/cancel` (`export.run.cancel`) SHALL request cancellation exactly as the
requester's own cancel does and answer `202` with the envelope; cancelling a terminal run SHALL return its
envelope, not `409`.

#### Scenario: An administrator cancels a runaway export
- **WHEN** an administrator cancels another user's `RUNNING` run
- **THEN** the response is `202`, the run reaches `CANCELLED` cooperatively, and the audit event names the actor and
  the requester
