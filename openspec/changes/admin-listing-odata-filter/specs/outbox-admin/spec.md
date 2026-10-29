## MODIFIED Requirements

### Requirement: Messages are browsable by state without their payload

`outbox-spring-boot-starter` SHALL declare `outbox.read`, `outbox.operate` and `outbox.privileged`, and serve under
`outbox.read`:

- `GET /admin/v1/outbox/messages?$filter=&cursor=` - newest first, at most 200 per page, keyset-paginated;
- `GET /admin/v1/outbox/messages/{id}` - one message with its status history and its last error as stored;
- `GET /admin/v1/outbox/summary` - the count per status and destination.

No response SHALL contain the message payload or its headers' values, and a listing SHALL NOT contain the last
error, whose text may quote the payload.

#### Scenario: What is dead-lettered
- **WHEN** an operator lists messages with `$filter=status eq 'DEAD_LETTER'`
- **THEN** each entry carries id, event type, destination, attempts and timestamps, and none carries the
  payload or the last error

## ADDED Requirements

### Requirement: Outbox messages are filterable on their routing and state
`$filter` on `GET /admin/v1/outbox/messages` SHALL accept `status`, `eventType`, `eventVersion`, `aggregateType`,
`aggregateId`, `destination`, `transport`, `attempts`, `createdAt`, `nextAttemptAt`, `publishedAt` and `finishedAt`.
The payload, the last error, the idempotency key and the lock owner SHALL NOT be filterable.

#### Scenario: Old dead letters of one event type
- **WHEN** an operator lists `$filter=status eq 'DEAD_LETTER' and eventType eq 'OrderCreated' and createdAt lt
  2026-10-26T00:00:00Z`
- **THEN** only such messages are returned, without payload or error text
