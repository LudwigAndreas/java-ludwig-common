## Purpose

Replaces the `reconciliation` actuator endpoint with administrative REST resources, keeping every operator action it
offered and classifying each into the platform's operation tiers, so the controls needed at three in the morning are
reachable, authorized per service role and audited.

## ADDED Requirements

### Requirement: Task state is readable

`reconciliation-spring-boot-starter` SHALL declare `reconciliation.read`, `reconciliation.operate` and
`reconciliation.privileged`, and serve under `reconciliation.read`:

- `GET /admin/v1/reconciliation/tasks` - every registered task with its tiers, cursor, freshness, last run outcome
  and quarantine count;
- `GET /admin/v1/reconciliation/tasks/{task}` - one task, with up to 20 quarantined records' keys and reasons and
  its open remote jobs and quota leases;
- `GET /admin/v1/reconciliation/tasks/{task}/scopes/{scope}` - one enumeration scope's state.

An unknown task or scope SHALL be `404`. A scope key containing `/` SHALL be percent-encoded by the caller.

#### Scenario: An operator lists the tasks
- **WHEN** a caller holding `reconciliation.read` lists the tasks
- **THEN** each registered task appears once with its state, and no quarantined payload is included

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

A run or backfill SHALL answer `202` with the platform operation envelope and a status header naming the task
resource; the pass runs to completion before the answer, so the envelope is terminal. A request the task's state
does not permit SHALL be `409`. An unknown task, or a scope action on a task that is not an enumeration, SHALL be
`404`.

#### Scenario: A run is started
- **WHEN** an operator starts a run of task `supplier-sync` on tier `hot`
- **THEN** the response is `202` with an operation envelope, the status header names
  `/admin/v1/reconciliation/tasks/supplier-sync`, and one audit event records the run

#### Scenario: A run is requested while one is in progress
- **WHEN** the task is already running on any replica
- **THEN** the response is `409` and no second run starts

### Requirement: Actions that can desynchronize are the privileged tier

Under `reconciliation.privileged`, with a mandatory `Admin-Reason`, the area SHALL serve:

| former actuator action | endpoint |
|---|---|
| `cancel-job` | `POST /admin/v1/reconciliation/tasks/{task}/remotejobs/{jobId}/cancel` |
| `release-lease` | `POST /admin/v1/reconciliation/leases/{leaseId}/release` |
| scope `confirm-sweep` | `POST /admin/v1/reconciliation/tasks/{task}/scopes/{scope}/sweep/confirm` |

`ludwig.reconciliation.endpoint.allow-destructive-operations` and the `confirm` parameter SHALL no longer exist:
whether a deployment permits these is whether any of its roles is granted `reconciliation.privileged`.

#### Scenario: A remote job is cancelled
- **WHEN** an administrator cancels remote job `j-9` with reason `partner asked to abort`
- **THEN** the job is cancelled and settled as before, and the audit event records the reason

#### Scenario: A privileged action without a reason
- **WHEN** the same request carries no `Admin-Reason`
- **THEN** the response is `400` and the job is untouched

### Requirement: The actuator endpoint no longer exists

The `reconciliation` actuator endpoint and its auto-configuration SHALL be removed. Adding `reconciliation` to the
exposure list SHALL expose nothing.

#### Scenario: A deployment still exposes the old endpoint id
- **WHEN** `management.endpoints.web.exposure.include` contains `reconciliation`
- **THEN** `/actuator/reconciliation` is `404` and the application starts
