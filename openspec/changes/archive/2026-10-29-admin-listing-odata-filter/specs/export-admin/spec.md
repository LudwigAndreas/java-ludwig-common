## MODIFIED Requirements

### Requirement: Every requester's runs and subscriptions are readable

`export-spring-boot-starter` SHALL declare `exports.read`, `exports.operate` and `exports.privileged`, and serve under
`exports.read`:

- `GET /admin/v1/exports/runs?$filter=&cursor=` and `GET /admin/v1/exports/runs/{runId}` - each run in the platform
  operation envelope with its requester and report, without its output;
- `GET /admin/v1/exports/subscriptions?owner=&report=` - each subscription with its schedule, run-as identity and
  last run.

The requester-scoped endpoints SHALL keep answering `404` to a caller who is not the requester; administrators use
this area instead.

#### Scenario: An administrator finds a stuck run of another user
- **WHEN** a caller holding `exports.read` lists runs with `$filter=status eq 'RUNNING'`
- **THEN** runs of every requester are listed, and no response offers the run's output

## ADDED Requirements

### Requirement: Export runs are filterable on their definition, requester and state
`$filter` on the administrative export runs listing SHALL accept `definitionKey`, `requester`, `status`, `attempts`,
`failureCode`, `createdAt`, `startedAt` and `finishedAt`. The stored filter expression, the failure arguments and the
idempotency key SHALL NOT be filterable.

#### Scenario: One requester's failed runs
- **WHEN** an operator lists runs with `$filter=requester eq 'alice' and status eq 'FAILED'`
- **THEN** only alice's failed runs are returned
