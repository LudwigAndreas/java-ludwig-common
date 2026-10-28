# messaging-db-admin Specification

## Purpose
Shows how far each database-log consumer is behind its partitions, read-only, so an operator can tell a slow
consumer from a stuck one without querying the offset tables.
## Requirements
### Requirement: Consumer lag is readable

`messaging-db-spring-boot-starter` SHALL declare `messaging.read`, `messaging.operate` and `messaging.privileged`, and
serve `GET /admin/v1/messaging/consumers` (`messaging.read`): for each consumer group and partition, the committed
offset, the partition head, the lag, and the consumer's retry state (attempts, next attempt time). The stored last error SHALL NOT be returned, because
its text may quote a payload. No endpoint SHALL move an offset in this release.

#### Scenario: A stuck consumer
- **WHEN** a consumer's committed offset has not changed while the head advanced
- **THEN** the response shows a growing lag for that partition, with its attempts and next attempt time

#### Scenario: No offset can be moved
- **WHEN** a request other than `GET` reaches `/admin/v1/messaging/consumers`
- **THEN** it is answered `405` and no offset changes

