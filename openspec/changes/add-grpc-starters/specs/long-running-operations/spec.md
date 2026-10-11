## ADDED Requirements

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
