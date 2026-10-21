## ADDED Requirements

### Requirement: Only the canonical header names are on the wire
The outbox dispatcher SHALL write each envelope header under its canonical `ludwig-` name only, and
`EnvelopeReader` and `VersionGatingDeserializer` SHALL read only those names. No pre-prefix spelling
(`event-type`, `event-version`, `idempotency-key`, `trace-id`) is written or read.

#### Scenario: The dispatcher produces a record
- **WHEN** `KafkaOutboxDispatcher` sends an outbox message
- **THEN** the record carries `ludwig-event-type`, `ludwig-event-version`, `ludwig-idempotency-key`
  and `ludwig-trace-id`, and none of `event-type`, `event-version`, `idempotency-key` or `trace-id`

#### Scenario: A record carries only a pre-prefix name
- **WHEN** a consumer reads a record whose only type header is `event-type`
- **THEN** its envelope's event type is `null` and its version is the default version, exactly as for a
  record with no such header

## REMOVED Requirements

### Requirement: Legacy header spellings are written and read during the migration window
**Reason**: Both of its conditions are met for 3.0.0. Every producer since v1.1.0 writes the canonical
names, and every consumer since v1.1.0 reads them first. Only records from a pre-1.1.0 producer
still depend on the fallback.
**Migration**: Before upgrading, confirm that no consumer older than 1.1.0 is deployed and that no topic a
consumer may replay still holds records produced before 1.1.0. Code that referenced
`MessageHeaders.LEGACY_*` uses the canonical constant of the same suffix.
