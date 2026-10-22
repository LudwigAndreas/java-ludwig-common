# The long-running-operation envelope

## Purpose
`ru.ludwigandreas.webcore.operation` is the one vocabulary for long-running work on this
platform. Five modules had each named the states for themselves and they disagreed — export
called a finished run `SUCCEEDED`, file-ingest called the same thing `COMPLETED`, one state in
two words in one platform, visible in two APIs.
## Requirements
### Requirement: One status vocabulary, and no module may restate it
`OperationStatus` SHALL be `PENDING RUNNING SUCCEEDED FAILED CANCELLED EXPIRED`, owned by
`web-core`. A module SHALL NOT declare a status enum that restates those six states.

#### Scenario: A module declares its own run-status enum
- **WHEN** a module introduces an enum that is those six states under other names, as export's
  `RunStatus` and file-ingest's `IngestRunStatus` were
- **THEN** `architecture-rules`' `RuleGroup.OPERATIONS` fails the build. Reconciling those two
  cost a Liquibase changeset, because `COMPLETED` had been persisted

#### Scenario: A module has a genuinely richer domain lifecycle
- **WHEN** a module's lifecycle carries information the six constants cannot, as
  reconciliation's `COLLECTING`, `PENDING_SUBMIT` and `ORPHANED` do
- **THEN** it keeps that lifecycle, maps it onto the core vocabulary, and surfaces it in
  `OperationResponse.detail()`. A shared vocabulary that erased it would be a downgrade

### Requirement: EXPIRED and CANCELLED are terminal and are not failures
Clients SHALL branch on `status.isFailure()`, not on `!= SUCCEEDED`.

#### Scenario: A result is removed by retention
- **WHEN** a succeeded operation's result is removed and the record moves to `EXPIRED`
- **THEN** it is terminal and not a failure. A client treating every non-`SUCCEEDED` terminal
  state as an error will page somebody for routine housekeeping

### Requirement: There is no shared operation table and web-core has no persistence dependency
Each module SHALL keep its own run table and map onto the envelope at its edge.
`web-core-spring-boot-starter` SHALL NOT acquire a persistence dependency.

#### Scenario: A change proposes a shared operation table
- **WHEN** a change would add a generic run table behind the envelope
- **THEN** it is refused. Four modules already track runs in tables designed for their own
  domain; a generic table would duplicate all four, add a write to every transition, and be worse
  at every one of their queries

### Requirement: Responses are built with OperationResponses, which refuses malformed envelopes
Producers SHALL build responses with `OperationResponses` rather than by hand.

#### Scenario: A 202 is returned with no status-resource header
- **WHEN** a submit returns `202` without naming a status resource
- **THEN** `OperationResponses.accepted` refuses it, because a client told to poll and not told
  where is exactly what export used to do

#### Scenario: A non-terminal poll omits Retry-After
- **WHEN** `OperationResponses.poll` is given a non-terminal envelope with no `Retry-After`
- **THEN** it is refused. Nothing in this platform emitted `Retry-After` before the contract, so
  every client invented its own interval and they all picked one second

#### Scenario: A terminal success carries no result, or a terminal failure carries no failure
- **WHEN** a terminal envelope omits its evidence
- **THEN** it is refused, on the first call in the first test rather than in a client's
  integration six weeks later

### Requirement: The caller chooses which header names the status resource
A `202` SHALL carry either `Location` or `Operation-Location`, chosen explicitly. There SHALL be
no default.

#### Scenario: The operation is the resource the client will read
- **WHEN** a submit creates something whose status monitor is the resource itself, as export's
  run and notification's request are
- **THEN** `Location` is used

#### Scenario: The eventual result has a different URL from the status monitor
- **WHEN** a submit slowly creates something the client will then `GET` elsewhere
- **THEN** `Operation-Location` is used. A helper that picked silently would pick `Location` for
  everybody, and the modules needing the other one would be the ones that never found out

### Requirement: A 202 may carry a terminal envelope, and a 200 must
A `200` from a submit SHALL carry a terminal envelope. A `202` MAY carry one, provided it still
names a status resource and still owes its evidence.

#### Scenario: A submit completes synchronously
- **WHEN** the work is already done when the response is written
- **THEN** `200` with a terminal envelope is explicitly permitted — a client should not poll for
  something already finished

#### Scenario: A request is fanned out in the transaction that accepts it
- **WHEN** notification accepts a request, fans it out in the same transaction, and the request
  operation is therefore finished by the time the response is written
- **THEN** it still answers `202`, because what it created is a queued intention and whether
  anything reaches anybody is decided minutes later by a provider it does not control. A `200`
  there would be a different promise

#### Scenario: A 202 reports SUCCEEDED with nothing to fetch
- **WHEN** a terminal-success `202` names no result link
- **THEN** it is refused. A terminal envelope owes its evidence whatever the status code

### Requirement: Cancellation is cooperative, so a cancel endpoint answers 202
A cancel endpoint SHALL answer `202`, not `204`. Cancelling an already-terminal operation SHALL
return the current envelope, not `409`.

#### Scenario: A client cancels a running operation
- **WHEN** a cancel request is accepted
- **THEN** `202`. The operation stops when it next checks its `Cancellation`, on whichever
  instance is running it, so all the endpoint can truthfully report is that the request was
  recorded. A `204` claims the work has stopped, which is a lie clients build retry logic on

#### Scenario: A client cancels an operation that has already finished
- **WHEN** the operation is already terminal
- **THEN** `OperationResponses.cancellationRequested` returns the current envelope with its
  terminal state. A `409` invites a retry loop over something that will never change

### Requirement: Progress has a nullable total
`OperationProgress` SHALL allow a null total, and SHALL serialize it as an absent member.

#### Scenario: A producer cannot know the denominator
- **WHEN** file-ingest reports progress on a streaming file whose record count is unknown until
  it has been read
- **THEN** the total is absent from the document. Requiring a denominator forces every producer
  to lie or to omit progress entirely, and `"total": 0` reads to a client as "done, and then
  some"

### Requirement: A submit endpoint does not blur 202 with the idempotency 409
A submit accepting an `Idempotency-Key` SHALL answer `202` only when it accepted **new** work.

#### Scenario: A duplicate submit finds work already in flight
- **WHEN** a retried submit carries a key whose work is running
- **THEN** it answers `409` with `Retry-After`, not `202`. A `202` would be telling the caller it
  had started a second run

### Requirement: The gRPC operation message is a mapping of the one envelope
A long-running operation exposed over gRPC SHALL be described by the platform's single shared
operation message, which carries the same status vocabulary, detail, progress, result reference,
failure and retry-after as the HTTP envelope and is produced from it by one mapper. A contract
SHALL import that message and SHALL NOT declare an operation status enum of its own.

#### Scenario: A running operation is returned over gRPC
- **WHEN** a handler returns an operation whose envelope is `RUNNING` with a retry-after of five
  seconds and a domain detail
- **THEN** the caller receives the shared operation message with status running, a retry-after of
  five seconds and the same detail

#### Scenario: Every envelope status has a wire value
- **WHEN** a status is added to the platform's operation vocabulary
- **THEN** the shared contract module's build fails until the operation message maps it

#### Scenario: A contract declares its own operation status enum
- **WHEN** a contract module's generated code contains an enum restating the six operation states
- **THEN** the operation-vocabulary architecture rule fails for a module that analyses it

### Requirement: The shared operation message is the only permitted restatement
The no-restatement rule SHALL exempt exactly one type, the status enum of the shared operation
message, by name, and SHALL continue to fail on any other enum restating the six states.

#### Scenario: The exempted type is analysed
- **WHEN** the operation-vocabulary rule analyses the shared contract module
- **THEN** it passes

#### Scenario: A second restatement is analysed alongside it
- **WHEN** the rule analyses a fixture that contains the exempted type and one other enum restating
  the states
- **THEN** it fails and names the other enum

