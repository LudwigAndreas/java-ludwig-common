## Purpose

Bounds how long the transactional outbox keeps messages that will never be sent again, and makes every deletion
visible in the audit trail.

## ADDED Requirements

### Requirement: Terminal messages are purged after their retention period
The outbox SHALL delete `PUBLISHED` messages whose `finished_at` is older than `ludwig.outbox.retention.published`
(default `P7D`), and `DEAD_LETTER` and `DISCARDED` messages whose `finished_at` is older than
`ludwig.outbox.retention.terminal` (default `P30D`), together with their `outbox_status_history` rows. It SHALL NOT
delete a `PENDING`, `PROCESSING` or `FAILED` message.

#### Scenario: An old published message is purged
- **WHEN** a `PUBLISHED` message finished eight days ago and the purge runs with default settings
- **THEN** the message row and its history rows no longer exist

#### Scenario: A recent dead letter is kept
- **WHEN** a `DEAD_LETTER` message finished ten days ago and the purge runs with default settings
- **THEN** the message is still listed by `GET /admin/v1/outbox/messages?status=DEAD_LETTER`

#### Scenario: A failed message awaiting retry is never purged
- **WHEN** a `FAILED` message was created a year ago and the purge runs
- **THEN** the message still exists

### Requirement: Retention can be disabled and is validated
`ludwig.outbox.retention.enabled=false` SHALL stop the purge job from being registered. A `published` period
shorter than one day, or a non-positive period, SHALL fail application startup, naming the property.

#### Scenario: Retention disabled
- **WHEN** `ludwig.outbox.retention.enabled=false`
- **THEN** no job named `ludwig-outbox-retention-purge` appears in `GET /admin/v1/jobs` and no row is deleted

#### Scenario: A period that would defeat idempotency
- **WHEN** `ludwig.outbox.retention.published=PT1H`
- **THEN** the application fails to start with a message naming `ludwig.outbox.retention.published`

### Requirement: Exactly one replica purges, in bounded batches
The purge SHALL run under the run lock `ludwig-outbox-retention-purge`, SHALL delete at most
`ludwig.outbox.retention.batch-size` messages per committed batch, and SHALL be a registered job that
`POST /admin/v1/jobs/ludwig-outbox-retention-purge/run` can start.

#### Scenario: Two replicas reach the schedule together
- **WHEN** two replicas run the purge schedule at the same time
- **THEN** only the replica holding the lease deletes rows

#### Scenario: A backlog larger than one batch
- **WHEN** 1,200 expired `PUBLISHED` messages exist and `batch-size` is 500
- **THEN** they are deleted in three committed batches, and a failure in the third leaves the first 1,000 deleted

### Requirement: Every purge is audited
After each batch that deleted at least one message commits, the outbox SHALL record an audit event with category
`outbox`, action `outbox.purged`, and attributes naming the status, the cutoff and the number deleted. A batch that
deleted nothing SHALL record nothing.

#### Scenario: A purge removes dead letters
- **WHEN** a pass deletes 3 expired `DEAD_LETTER` messages
- **THEN** the audit trail holds one `outbox.purged` event with `status=DEAD_LETTER` and `count=3`

### Requirement: Retention is measured from when a message became terminal
`outbox_message.finished_at` SHALL be set when a message becomes `PUBLISHED`, `DEAD_LETTER` or `DISCARDED`, and
SHALL be cleared when the message leaves a terminal status. Rows that existed before the migration SHALL be given
`published_at` when published, and the migration time otherwise.

#### Scenario: A pre-existing dead letter after upgrade
- **WHEN** a `DEAD_LETTER` row from before the upgrade exists and the purge runs a day after the upgrade
- **THEN** it is kept, because its retention counts from the migration
