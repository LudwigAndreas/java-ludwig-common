## MODIFIED Requirements

### Requirement: The module's lifecycle is a domain lifecycle and does not restate OperationStatus
`FileActionState` SHALL be `UPLOADED VALIDATED APPLYING APPLIED REJECTED EXPIRED CANCELLED`, SHALL map onto
`OperationStatus`, and SHALL be published in `OperationResponse.detail()`. Its non-terminal and success values SHALL
carry domain information the core states cannot (`UPLOADED`, `VALIDATED`, `APPLYING`, `APPLIED`), so that the enum
is a lifecycle that maps onto the core vocabulary rather than a restatement of it.

#### Scenario: A submission is awaiting confirmation

- **WHEN** a `CONFIRM`-mode submission has been validated and nobody has confirmed it
- **THEN** a poll returns `status: PENDING` with `detail: VALIDATED`

#### Scenario: Someone declares an enum that only restates the core states

- **WHEN** a change declares an enum whose every constant is a core-vocabulary word, spanning a pending, a success,
  a failure and a stop or expiry constant
- **THEN** `RuleGroup.OPERATIONS` fails the build

#### Scenario: A submission is cancelled after it has already been applied

- **WHEN** `POST /file-actions/{action}/{id}/cancel` names a submission in `APPLIED`
- **THEN** the response is `200` with the terminal envelope, not `409`, and the submission stays `APPLIED`

#### Scenario: A running submission is cancelled

- **WHEN** `POST /file-actions/{action}/{id}/cancel` names a submission in `APPLYING`
- **THEN** the response is `202`, because the stop is requested cooperatively and not yet achieved

## ADDED Requirements

### Requirement: A cancelled submission ends CANCELLED
A submission whose cancellation was requested SHALL end in `CANCELLED` (envelope `status: CANCELLED`) unless it
reached another terminal state first. It SHALL NOT end `APPLIED`. `rowsApplied` SHALL report the rows kept before
the stop. The stop SHALL be audited as `file-action.cancelled`, and no `file-action.applied` event SHALL be
recorded for it.

#### Scenario: A deferred submission is cancelled before the worker applies it

- **WHEN** a `DEFERRED` submission in `UPLOADED` is cancelled and the worker then claims it
- **THEN** the handler is not called for any row, a poll returns `status: CANCELLED` with `detail: CANCELLED` and
  `rowsApplied: 0`, and the audit trail holds `file-action.cancelled` and no `file-action.applied`

#### Scenario: A PER_BATCH submission is cancelled between batches

- **WHEN** a `PER_BATCH` submission is cancelled after its first batch committed and before its second started
- **THEN** it ends `CANCELLED` with `rowsApplied` equal to the first batch's applied rows, and no later batch is
  applied

#### Scenario: A submission awaiting confirmation is cancelled

- **WHEN** `POST /file-actions/{action}/{id}/cancel` names a submission in `VALIDATED`
- **THEN** the response is `200` with `status: CANCELLED`, and a later confirm of it answers `409`

#### Scenario: A cancelled submission is cancelled again

- **WHEN** a cancel names a submission already in `CANCELLED`
- **THEN** the response is `200` with the same terminal envelope and no further audit event of the stop
