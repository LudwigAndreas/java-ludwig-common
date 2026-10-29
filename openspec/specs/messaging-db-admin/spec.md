# messaging-db-admin Specification

## Purpose
Shows how far each database-log consumer is behind its partitions, so an operator can tell a slow consumer from a
stuck one without querying the offset tables, and lets the operator pause a group on a topic and move its position
to skip a poison record or replay after a fix.
## Requirements
### Requirement: Consumer lag is readable

`messaging-db-spring-boot-starter` SHALL declare `messaging.read`, `messaging.operate` and `messaging.privileged`, and
serve `GET /admin/v1/messaging/consumers` (`messaging.read`): for each consumer group and partition, the committed
offset, the partition head, the lag, and the consumer's retry state (attempts, next attempt time), and for each consumer group and topic whether it is
paused, by whom and since when. The stored last error SHALL NOT be returned, because its text may quote a payload.

#### Scenario: A stuck consumer
- **WHEN** a consumer's committed offset has not changed while the head advanced
- **THEN** the response shows a growing lag for that partition, with its attempts and next attempt time

#### Scenario: A paused consumer is visible
- **WHEN** group `billing` is paused on `catalog.products`
- **THEN** the listing shows that pair as paused with the subject who paused it and when

### Requirement: A consumer group can be paused and resumed on a topic
`POST /admin/v1/messaging/consumers/{group}/topics/{topic}/pause` and `.../resume` (`messaging.operate`) SHALL pause or
resume delivery of that topic's log records to that group on every replica. A pause SHALL take effect no later than
each replica's next claim, after at most one in-flight record per partition completes. Both SHALL be idempotent and
audited. A group this service's own listeners do not consume as, or a group with no position on the topic,
SHALL be `404`.

#### Scenario: Pausing a group stuck on a poison record
- **WHEN** an operator pauses group `billing` on `catalog.products` in a service with three replicas
- **THEN** within one poll interval no replica delivers a `catalog.products` record to `billing`, other topics and
  other groups are unaffected, and one audit event records the pause

#### Scenario: Resuming
- **WHEN** the operator resumes the pair
- **THEN** delivery continues from the committed position

### Requirement: A paused group's position can be moved
`POST /admin/v1/messaging/consumers/{group}/topics/{topic}/offsets` (`messaging.privileged`, reason required) SHALL move
the group's committed position on one partition or all partitions of the topic to `earliest`, `latest`, an explicit
offset, or the first record at or after a timestamp, clamped to the retained log. It SHALL be refused with `409`
unless the pair is paused, and with `400` for an unrecognised target or unparseable value. It SHALL reset the partitions' retry state, and SHALL answer with each partition's old and
new position and the number of records skipped or to be replayed. The move SHALL be audited with the reason,
positions and counts.

#### Scenario: Skipping a poison record
- **WHEN** a paused group is moved on partition 2 to the offset after the poison record, with a reason
- **THEN** the response shows one record skipped on partition 2, and after resume the next record delivered on that
  partition is the one after it

#### Scenario: Replaying after a downstream fix
- **WHEN** a paused group is moved on all partitions to a timestamp one day earlier
- **THEN** the response shows the replay count per partition, and after resume those records are delivered again in
  offset order, except those the consumer's dedup still holds

#### Scenario: Moving a running group is refused
- **WHEN** the move is requested for a pair that is not paused
- **THEN** the response is `409` and no position changes
