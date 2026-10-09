## MODIFIED Requirements

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
