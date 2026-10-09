# The audit envelope and redaction

## Purpose
`audit-core` provides the platform's one `AuditSink` and one `AuditEvent` envelope. Nine modules
once had nine audit mechanisms; this is the one that replaced them.

## Requirements

### Requirement: One audit sink, and no module-local audit SPI
A module SHALL record through `audit-core`'s `AuditSink`. It SHALL NOT declare an audit SPI of
its own, and SHALL NOT create a logger named `*.audit`. A module SHALL NOT substitute a framework
notification mechanism - a Spring application event, a listener interface, a callback bean - for the sink:
not using the sink is a way of having a second audit mechanism, and the module that does it is the one
module whose trail a deployment cannot configure, redact or ship to a SIEM.

#### Scenario: A module needs to record an auditable action
- **WHEN** a module has a state change that belongs in the trail
- **THEN** it keeps its typed event record as the authoring surface — thirteen components
  assembled positionally are readable and thirteen map entries are not — gives it a
  `toAuditEvent()`, and hands the result to the shared `AuditSink`

#### Scenario: A module declares a second audit SPI
- **WHEN** a module introduces its own sink interface or an audit-named logger
- **THEN** `architecture-rules`' `RuleGroup.AUDIT` fails the build

#### Scenario: A module publishes an application event and calls that its audit trail
- **WHEN** a module publishes a framework event and documents it as the way to build an audit trail,
  leaving each consuming service to write the forwarding listener
- **THEN** it has not recorded anything. Every consumer writes the same listener or none does, the
  deployment's `AuditFailurePolicy` governs nothing, and the event's contents have passed through no
  redaction. The module SHALL hand its event to the sink itself

#### Scenario: A module keeps an application event alongside the sink
- **WHEN** a module publishes an in-process event *and* records through the sink
- **THEN** that is permitted. An `@EventListener` is a legitimate extension point for a service to want;
  what is forbidden is the event being the audit mechanism rather than an addition to it

#### Scenario: An event record carries a value a caller supplied
- **WHEN** a module's event would carry a query expression, a request body, a comparison value or any
  other caller-supplied content
- **THEN** it records what was touched and how — the resource, the property paths named, the operations
  used — and masks any value with `Redaction.MASK`, because an audit trail is retained for years and read
  by people not entitled to the data it guards

#### Scenario: `RuleGroup.AUDIT` passes a module that uses no sink at all
- **WHEN** a module declares no sink interface and no audit-named logger, and also calls no sink
- **THEN** the rule group as it stands reports no violation, which is the gap that let
  `odata-filter-spring-boot-starter`'s `FilterAppliedEvent` stand as a module's whole audit mechanism. The
  rule group SHALL additionally fail a module that declares a **top-level record** in an `audit` package
  which declares no method returning an `AuditEvent`

#### Scenario: An `audit` package holds machinery rather than an event
- **WHEN** a module's `audit` package also contains a recorder, a listener, an actor resolver, a
  correlation provider, a constants holder or a generated builder - which between them describe most of what
  is actually in the platform's `audit` packages, and in `db-core`'s case JPA's unrelated sense of
  "auditing"
- **THEN** none of those is required to produce an envelope. The rule is anchored on a **record**, because a
  record in an audit package is a data carrier and therefore the event, while machinery that declares a seam
  is what the first requirement in this capability already catches. A first draft anchored on "any type"
  flagged `file-action`'s action-name constants and Lombok's generated builder, and was wrong to

#### Scenario: A module evades the rule by renaming or by using a class
- **WHEN** a module moves its event out of the `audit` package, or declares it as a class rather than a
  record
- **THEN** the rule passes and no check can say otherwise. Both evasions are visible in review in a way the
  original silence was not, which is the whole of the improvement; the rule's javadoc names them

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
