# identity-source-kafka Specification

## Purpose

Feeds the identity projection from a provider that publishes user changes to Kafka, one subscription per declared
source, through a published event contract that tolerates replay, reordering and unknown fields.
## Requirements
### Requirement: Each subscription feeds one declared source
With the Kafka source starter present, every entry under `ludwig.identity.kafka.subscriptions.<source>` SHALL
consume its `topic` into the projection as changes of `<source>`, and `<source>` SHALL be declared under
`ludwig.identity.sources`. A subscription without a topic SHALL fail startup, naming the subscription.

#### Scenario: Two providers, two topics
- **WHEN** `subscriptions.staff.topic` is `corp.staff.users` and `subscriptions.customers.topic` is
  `shop.customer.users`
- **THEN** an event on `corp.staff.users` is applied as a change of `staff`, and one on `shop.customer.users` as a
  change of `customers`

### Requirement: Every service consumes with its own group
`ludwig.identity.kafka.group-id` SHALL be required whenever a subscription exists, and the application SHALL fail to
start without it, naming the key: every service holds its own complete copy, and a group shared with another
service would receive only part of the stream.

#### Scenario: No consumer group
- **WHEN** a subscription is configured and `ludwig.identity.kafka.group-id` is not set
- **THEN** the application fails to start, naming `ludwig.identity.kafka.group-id`

### Requirement: The event contract is published and tolerant
An event SHALL be a JSON object with `eventId`, `type` (`UPSERT`, `DISABLE` or `DELETE`), `subject`,
`occurredAt` and, for `UPSERT`, `displayName`, `tenantId`, `roles` (the complete set) and the optional contact
fields and their verification flags, plus an optional `sourceVersion`. Fields the contract does not name SHALL be
ignored. `DELETE` SHALL be applied as a remove.

#### Scenario: The provider adds a field
- **WHEN** an event carries a field `department` the contract does not name
- **THEN** the event is applied as if the field were absent

### Requirement: A malformed event is dead-lettered at once; a failed apply is retried
An event that is not valid JSON or not an object of the contract SHALL be dead-lettered on its first delivery and
SHALL NOT block the partition. An event that fails while being applied (for example, two replicas inserting the same
new user at once) SHALL be retried under the messaging starter's consumer retry policy, and SHALL NOT be
acknowledged until it is applied or dead-lettered.

#### Scenario: A malformed event
- **WHEN** an event with the payload `not json` arrives
- **THEN** it is on the dead-letter topic after one delivery, and the next event on the partition is applied

#### Scenario: A concurrent first insert
- **WHEN** two replicas apply the first upsert for the same new subject at the same moment
- **THEN** both deliveries end with one row holding that upsert's state
