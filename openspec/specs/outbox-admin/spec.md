# outbox-admin Specification

## Purpose
Lets an operator see what the transactional outbox is holding back and act on it - retry what failed, discard what
must never be sent - without SQL and without ever seeing a message's payload.
## Requirements
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

### Requirement: Failed and dead-lettered messages can be retried

`POST /admin/v1/outbox/messages/{id}/retry` (`outbox.operate`) SHALL move a `FAILED` or `DEAD_LETTER` message to
`PENDING` with its attempt count reset, recording the transition in its status history. Any other status SHALL be
`409`. `POST /admin/v1/outbox/messages/retry` (`outbox.operate`) with a filter of status (`FAILED` or
`DEAD_LETTER`, required), and optionally event type, destination and created-before, SHALL retry at most 1000
matching messages per call and report how many it moved and whether more match.

#### Scenario: A dead-lettered message is retried
- **WHEN** an operator retries a `DEAD_LETTER` message
- **THEN** it becomes `PENDING` with attempts zero, its history shows the administrative transition, the audit
  event names the actor, and the next poll publishes it

#### Scenario: A published message is retried
- **WHEN** the message is `PUBLISHED`
- **THEN** the response is `409` and the message is unchanged

#### Scenario: A bulk retry larger than the cap
- **WHEN** 2500 messages match
- **THEN** 1000 are moved, the response reports `moved: 1000, more: true`, and one audit event records the filter and
  the count

### Requirement: A message can be discarded, and stays on record

`POST /admin/v1/outbox/messages/{id}/discard` (`outbox.privileged`, reason required) SHALL move a `PENDING`,
`FAILED` or `DEAD_LETTER` message to the new terminal status `DISCARDED`, which no poller claims. The row and its
history SHALL be kept and purged by the same retention as `DEAD_LETTER`.

#### Scenario: A poison message is discarded
- **WHEN** an administrator discards a `DEAD_LETTER` message with reason `superseded by INC-4411 fix`
- **THEN** it becomes `DISCARDED`, is never published, and its history and the audit event carry the reason

#### Scenario: A message being published is discarded
- **WHEN** the message is `PROCESSING`
- **THEN** the response is `409`

### Requirement: A retried message leaves retention
When an administrator retries a `DEAD_LETTER` message, its `finished_at` SHALL be cleared, so that the purge does
not delete it while it is being retried. If it reaches a terminal status again, its retention SHALL count from that
new transition.

#### Scenario: A dead letter retried on its twenty-ninth day
- **WHEN** a `DEAD_LETTER` message that finished 29 days ago is retried, fails again and becomes `DEAD_LETTER`
  again, and the purge runs two days later
- **THEN** the message still exists

### Requirement: Outbox messages are filterable on their routing and state
`$filter` on `GET /admin/v1/outbox/messages` SHALL accept `status`, `eventType`, `eventVersion`, `aggregateType`,
`aggregateId`, `destination`, `transport`, `attempts`, `createdAt`, `nextAttemptAt`, `publishedAt` and `finishedAt`.
The payload, the last error, the idempotency key and the lock owner SHALL NOT be filterable.

#### Scenario: Old dead letters of one event type
- **WHEN** an operator lists `$filter=status eq 'DEAD_LETTER' and eventType eq 'OrderCreated' and createdAt lt
  2026-10-26T00:00:00Z`
- **THEN** only such messages are returned, without payload or error text
