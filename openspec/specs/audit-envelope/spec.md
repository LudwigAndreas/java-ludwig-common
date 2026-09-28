# The audit envelope and redaction

## Purpose
`audit-core` provides the platform's one `AuditSink` and one `AuditEvent` envelope. Nine modules
once had nine audit mechanisms; this is the one that replaced them.

## Requirements

### Requirement: One audit sink, and no module-local audit SPI
A module SHALL record through `audit-core`'s `AuditSink`. It SHALL NOT declare an audit SPI of
its own, and SHALL NOT create a logger named `*.audit`.

#### Scenario: A module needs to record an auditable action
- **WHEN** a module has a state change that belongs in the trail
- **THEN** it keeps its typed event record as the authoring surface — thirteen components
  assembled positionally are readable and thirteen map entries are not — gives it a
  `toAuditEvent()`, and hands the result to the shared `AuditSink`

#### Scenario: A module declares a second audit SPI
- **WHEN** a module introduces its own sink interface or an audit-named logger
- **THEN** `architecture-rules`' `RuleGroup.AUDIT` fails the build

### Requirement: Attributes are redacted at construction, never at the sink
An `AuditEvent`'s `attributes` SHALL already be redacted when the event is constructed.

#### Scenario: A custom sink ships events to a SIEM or a database
- **WHEN** a deployment adds a sink that persists or forwards events
- **THEN** it cannot receive an unredacted event, because redaction happened at the point the
  entry was built. A sink that *can* receive one is a sink that will eventually write one
  somewhere permanent

### Requirement: The audit trail records which object was touched, never its contents
An `AuditEvent` SHALL NOT carry payloads.

#### Scenario: An event describes a change to a record
- **WHEN** an event is built for a change to a resource
- **THEN** it records the resource type, id and name and the action, and not the data in it. An
  audit trail is retained for years and read by people who are not entitled to the data it guards

### Requirement: One redaction mask, not configurable
`Redaction.MASK` SHALL be the single mask constant in the repository, a `static final` with no
property that moves it.

#### Scenario: A second mask constant is introduced anywhere
- **WHEN** a source file declares another masking constant
- **THEN** Checkstyle's `SecondRedactionMask` rule fails the build. It is a Checkstyle rule and
  not an ArchUnit one because a mask is the *value* of a string constant, which bytecode analysis
  cannot see

#### Scenario: A deployment wants to change the mask
- **WHEN** a deployment would prefer a different marker
- **THEN** it cannot. A deployment that could change it could set it to the empty string, at
  which point a redacted value and a value that was never set become the same row. A fixed marker
  is also chosen over a hash (reversible for any value from a small set, which most settings and
  every phone number are) and over a truncation (which leaks exactly the identifying part)

### Requirement: Sensitivity classifiers compose as a union
The classifiers SHALL compose with `SensitivityClassifier.anyOf`, never as a priority order in
which one can clear what another flagged.

#### Scenario: A value is sensitive by one classifier and innocuous by another
- **WHEN** a Vault-sourced value is named `timeout`, or a non-Vault value is named
  `client_secret`
- **THEN** it is treated as sensitive. Any rule letting one classifier clear another's flag is a
  rule that leaks; the cost of the union being wrong is a masked timeout in a log line

### Requirement: Body redaction is structural, not textual
JSON bodies SHALL be redacted by walking the parsed tree.

#### Scenario: A body contains the word "password" as a value, or a secret nested deeply
- **WHEN** a body is redacted
- **THEN** the structural walk masks the nested field and leaves the innocuous string value
  alone. A regular expression over raw JSON would do the opposite. It is slower and correct, and
  it only runs when a caller has explicitly opted into body logging

### Requirement: A sink may throw, and the policy decides whether the caller survives
Whether a sink failure fails the caller SHALL be decided by `AuditFailurePolicy`, resolved from
configuration and applied by `FailurePolicyAuditSink`. A module SHALL NOT wrap `record` in
try/catch.

#### Scenario: A module catches a sink failure itself
- **WHEN** a library puts a try/catch around `record`
- **THEN** it has overridden the deployment's configured policy with one hard-coded in a library.
  `AuthorizationDeniedAuditListener` is the platform's single exception, and its javadoc says why:
  it protects the authorization decision from *the listener*, not the caller from the sink

#### Scenario: A settings change cannot be audited
- **WHEN** the sink fails while recording a `settings` category event, whose default policy is
  `FAIL_OPERATION` and whose audit write is inside the caller's transaction
- **THEN** the exception is rethrown and the settings change rolls back, rather than a change
  standing with no trail — which is precisely what the trail exists to make impossible

#### Scenario: A sink fails for any other category
- **WHEN** the sink fails for a category defaulting to `LOG_AND_CONTINUE`
- **THEN** the caller survives, because failing a request over an audit write turns an audit
  outage into a service outage — and the operational response to that is invariably to switch the
  trail off

#### Scenario: A sink has been failing since a schema change
- **WHEN** a sink fails under any policy
- **THEN** an `AuditSinkFailureListener` is notified and the starter's Micrometer counter is
  incremented, so that a `LOG_AND_CONTINUE` failure is not a warning line nobody reads until an
  auditor asks why a month is missing
