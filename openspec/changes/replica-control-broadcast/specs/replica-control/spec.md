## Purpose

Lets an administrative control that acts on per-replica in-memory state reach every live replica of a service, and
reports per replica whether it took effect, instead of reporting success from whichever replica answered.

## ADDED Requirements

### Requirement: Live replicas are known from heartbeats
Every replica running `job-core` SHALL record a heartbeat at least once per `ludwig.job-core.control.poll-interval`.
`GET /admin/v1/replicas` (`replicas.read`) SHALL list the instances whose last heartbeat is within three poll
intervals, each with its owner id, start time and last-seen time, and the same answer from any replica.

#### Scenario: A replica is scaled away
- **WHEN** one of three replicas is stopped and four poll intervals pass
- **THEN** `GET /admin/v1/replicas` lists two instances

### Requirement: A control is broadcast to the replicas live at submission
`POST /admin/v1/replicas/controls/{control}` SHALL be authorized by the operation the control declares, SHALL validate
its arguments before anything is recorded, SHALL record a command targeting every instance live at that moment, and
SHALL answer `202` with the command's operation envelope and `Location` naming
`/admin/v1/replicas/commands/{id}`. An unknown control SHALL be `404`. Every targeted replica SHALL apply the
command at most once per process lifetime and acknowledge it with success or a failure code.

#### Scenario: A control reaches every replica
- **WHEN** an administrator submits a control in a service with three live replicas, wherever the request lands
- **THEN** within the acknowledgement timeout each of the three replicas has applied it once, and the command's
  envelope is `SUCCEEDED` with three successful acknowledgements

#### Scenario: Arguments are refused before broadcast
- **WHEN** a control's arguments fail validation
- **THEN** the response is `400` and no command is recorded

#### Scenario: The caller lacks the control's operation
- **WHEN** a caller holding `replicas.read` but not the control's declared operation submits it
- **THEN** the response is `403` and no command is recorded

### Requirement: A command's status is the replicas' acknowledgements
`GET /admin/v1/replicas/commands/{id}` (`replicas.read`) SHALL return the command as the platform operation envelope
with one entry per targeted instance, each carrying the control's per-replica result when it returned one (at most
4 KiB). The status SHALL be `SUCCEEDED` when every target acknowledged success,
`FAILED` when any acknowledged failure, `FAILED` with code `ludwig.job.error.control-unacknowledged` when
`ludwig.job-core.control.ack-timeout` elapsed with a target silent, and `RUNNING` otherwise. A silent target SHALL be
named, never counted as done.

#### Scenario: A replica dies before acknowledging
- **WHEN** one of three targets stops before applying the command and the timeout elapses
- **THEN** the envelope is `FAILED` with `ludwig.job.error.control-unacknowledged`, two entries show success and
  one shows that instance as unacknowledged

#### Scenario: A control reports per-replica state
- **WHEN** a control returns a result on each replica
- **THEN** each instance's entry in the command resource carries that replica's own result

#### Scenario: A handler fails on one replica
- **WHEN** a control's handler throws on one replica
- **THEN** that replica's entry carries a failure code, the others still apply it, and the envelope is `FAILED`

### Requirement: Every control submission is audited
Submitting a control SHALL record one administrative audit event naming the control, its arguments as the envelope
redacts them, the targeted instances and the command id. A privileged control SHALL require `Admin-Reason`.

#### Scenario: A privileged control without a reason
- **WHEN** a control declaring a privileged operation is submitted without `Admin-Reason`
- **THEN** the response is `400` and no command is recorded
