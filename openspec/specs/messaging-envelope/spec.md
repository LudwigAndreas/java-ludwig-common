# The messaging envelope and error-handling convention

## Purpose
`messaging-spring-boot-starter` gives Kafka records one set of header names and one set of
error-handling defaults. Before it, the names lived in the dispatcher as string literals: one
consumer copied them with a comment saying the two had to agree, two gave up and parsed the
payload, and `eventVersion` reached production with nothing consuming it.

## Requirements

### Requirement: One set of envelope header names
A produced record SHALL carry the envelope headers `ludwig-event-type`,
`ludwig-event-version`, `ludwig-aggregate-type`, `ludwig-aggregate-id`,
`ludwig-idempotency-key`, `ludwig-trace-id` and `ludwig-produced-at`. A consumer SHALL read them
through `EnvelopeReader` rather than by repeating a literal.

#### Scenario: A consumer needs the event type
- **WHEN** a consumer dispatches on the kind of event
- **THEN** it reads `ludwig-event-type` via `EnvelopeReader`, rather than copying a string
  literal out of the dispatcher or parsing the payload to recover what the header already says

#### Scenario: A producer records when it emitted an event
- **WHEN** `ludwig-produced-at` is written
- **THEN** it is the producer's own ISO-8601 UTC instant, not the broker's timestamp

### Requirement: The correlation id keeps its cross-transport name
The correlation id SHALL travel as `X-Correlation-Id`, breaking the `ludwig-` prefix pattern on
purpose, and `EnvelopeReader` SHALL accept the configured override.

#### Scenario: A request crosses from HTTP to Kafka
- **WHEN** a correlation id set on an HTTP request is carried onto a produced record
- **THEN** it uses the same header name on both, because
  `observability-spring-boot-starter` already reads and writes that name on both. An id
  travelling as one name over HTTP and another over the broker is two ids as far as every log
  query is concerned

### Requirement: Legacy header spellings are written and read during the migration window
The dispatcher SHALL write both the canonical and the legacy spellings (`event-type`,
`event-version`, `idempotency-key`, `trace-id`), and `EnvelopeReader` SHALL read the canonical
name first and fall back to the legacy one.

#### Scenario: A consumer replays records produced before this module existed
- **WHEN** a consumer group replays from an offset predating the canonical names
- **THEN** it still reads the values, via the legacy fallback

#### Scenario: A change proposes dropping a legacy spelling
- **WHEN** the legacy **write** is dropped, it SHALL be only after every topic's retention
  window has passed with the new producer deployed; when the legacy **read** is dropped, it SHALL
  be only after no consumer group can still be asked to replay from before that point
- **THEN** dropping either earlier means a consumer reading `null` where a value exists — and for
  `ludwig-event-type` that is a record silently ignored as an unknown type, an error nowhere,
  with the replica simply never learning about that change

### Requirement: A malformed payload is dead-lettered, never retried forever
`ErrorHandlingDeserializer` SHALL wrap both key and value, and `DeserializationException` and
`MessageConversionException` SHALL be registered non-retryable.

#### Scenario: A record arrives with an unparseable payload
- **WHEN** deserialization fails
- **THEN** it becomes a record the error handler can classify and send straight to the
  dead-letter topic. Without the wrapper the exception is thrown before the listener is reached,
  the container has nothing to recover, and it retries the same bytes forever — the poison-pill
  stall. A payload that could not be deserialized will never deserialize, so it does not spend
  four attempts proving it

### Requirement: Auto-commit is off unconditionally
Offsets SHALL be committed by the container after the listener returns, never by the broker on a
timer.

#### Scenario: A database outage spans a period of traffic
- **WHEN** the listener fails for every record arriving during an outage
- **THEN** those offsets are not advanced. With auto-commit they would be, and those records
  would be gone — the failure that looks like "the notifications from Tuesday afternoon never
  arrived" and has no trace anywhere

### Requirement: Retry backoff lives in the container
Retries SHALL use `DefaultErrorHandler` with `ExponentialBackOff`, defaulting to 1s × 3 capped at
30s and four deliveries including the first, overridable per consumer.

#### Scenario: A record fails and is retried
- **WHEN** a listener throws and the record is retried
- **THEN** no thread is occupied waiting and the whole partition pauses, which keeps the retried
  record in order relative to the ones behind it

### Requirement: Dead-letter sends let the broker choose the partition
The recoverer SHALL publish to `<topic>.dlt` with partition `-1`.

#### Scenario: The dead-letter topic has fewer partitions than the source
- **WHEN** a record is dead-lettered
- **THEN** the broker chooses the partition. Preserving the source partition would require the
  dead-letter topic to have at least as many, which nothing enforces — and a send to a partition
  that does not exist fails silently

### Requirement: A failed dead-letter send is distinguished from a successful one
Dead-letter sends SHALL be counted as `ludwig.messaging.dead.lettered` and audited; a **failed**
send SHALL be counted separately as `ludwig.messaging.dropped` with its own audit action.

#### Scenario: The dead-letter send itself fails
- **WHEN** publishing to the dead-letter topic fails
- **THEN** it is counted and audited distinctly, because it is materially worse: the record is
  gone and the platform believes it has a safety net it does not have

### Requirement: Acknowledgement is per record unless a consumer opts out
Acknowledgement mode SHALL default to `RECORD`. `MANUAL` SHALL require setting
`ludwig.messaging.consumers.<name>.ack-mode`.

#### Scenario: A consumer is added with no ack-mode configured
- **WHEN** a new consumer is wired
- **THEN** it acknowledges per record, giving the smallest redelivery window: a record is
  committed when its listener returned, and nothing else in the poll is affected by its failure.
  Nobody inherits `MANUAL` by accident, which is how user-settings ended up with manual
  acknowledgement and no error handler

### Requirement: A listener does not declare an Acknowledgment parameter
A listener method SHALL NOT declare an `Acknowledgment` parameter.

#### Scenario: A listener declares one anyway
- **WHEN** a listener takes an `Acknowledgment`
- **THEN** it is contradicting the container's `RECORD` acknowledgement, and the module's rules
  reject it
