## Purpose

Gives administrators a view of every requester's export runs and subscriptions, which the requester-scoped API
deliberately hides, and lets them cancel a run that should not continue.

## ADDED Requirements

### Requirement: Every requester's runs and subscriptions are readable

`export-spring-boot-starter` SHALL declare `exports.read`, `exports.operate` and `exports.privileged`, and serve under
`exports.read`:

- `GET /admin/v1/exports/runs?requester=&report=&status=&cursor=` and `GET /admin/v1/exports/runs/{runId}` - each
  run in the platform operation envelope with its requester and report, without its output;
- `GET /admin/v1/exports/subscriptions?owner=&report=` - each subscription with its schedule, run-as identity and
  last run.

The requester-scoped endpoints SHALL keep answering `404` to a caller who is not the requester; administrators use
this area instead.

#### Scenario: An administrator finds a stuck run of another user
- **WHEN** a caller holding `exports.read` lists runs with status `RUNNING`
- **THEN** runs of every requester are listed, and no response offers the run's output

### Requirement: Any run can be cancelled

`POST /admin/v1/exports/runs/{runId}/cancel` (`exports.operate`) SHALL request cancellation exactly as the
requester's own cancel does and answer `202` with the envelope; cancelling a terminal run SHALL return its
envelope, not `409`.

#### Scenario: An administrator cancels a runaway export
- **WHEN** an administrator cancels another user's `RUNNING` run
- **THEN** the response is `202`, the run reaches `CANCELLED` cooperatively, and the audit event names the actor and
  the requester
