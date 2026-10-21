## Purpose

A temporary, removable stand-in for an owned Kafka cluster. Services write to a Kafka-shaped log in
the shared PostgreSQL database and read it back with Kafka's consumer semantics. Producers and
listeners stay exactly as they are, so the stand-in can be dropped by removing one dependency.

## ADDED Requirements

### Requirement: Every outbound record is written to the log while the transport is active

While the starter is on the classpath and `ludwig.messaging.db.mode` is `active` (the default), every
record that the outbox would have sent over its `kafka` transport SHALL be appended to the log. It
SHALL be appended under the same topic, key, headers and payload. No connection to a Kafka broker
SHALL be attempted for that write. Outbox routes SHALL NOT need to change, and SHALL keep naming the
`kafka` transport.

#### Scenario: An outbox event lands in the log

- **WHEN** a service with the starter, in `active` mode, publishes an outbox event routed to topic
  `catalog.products` with key `p-1`
- **THEN** after the outbox poll the log holds one row for topic `catalog.products` with key `p-1`,
  the event's payload, and the envelope headers (`ludwig-event-type`, `ludwig-event-version`,
  `ludwig-idempotency-key`, `X-Correlation-Id`, and the rest)
- **AND** the outbox row is recorded as published

#### Scenario: No broker is contacted to write

- **WHEN** the configured Kafka bootstrap address is unreachable and the outbox publishes an event
- **THEN** the event is in the log and the outbox row is published, not failed

### Requirement: Dead-letter records are written to the log, whatever the source topic

While the transport is `active`, a record that is dead-lettered SHALL be appended to the log under
`<source-topic>.dlt`, and this SHALL also apply when the source record was read from Kafka. A record
that failed deserialization SHALL be dead-lettered with its original bytes unchanged.

#### Scenario: A failing record from external Kafka is dead-lettered into the log

- **WHEN** a listener reading `partner.orders` from Kafka exhausts its delivery attempts on a record
- **THEN** the log holds a row for topic `partner.orders.dlt` carrying that record's key, headers and
  payload
- **AND** nothing is written to the Kafka cluster
- **AND** `ludwig.messaging.dead.lettered` is incremented and a dead-letter audit event is recorded

#### Scenario: A poison payload keeps its bytes

- **WHEN** a record in the log whose payload is not valid JSON reaches a listener with a typed payload
- **THEN** the `.dlt` row's payload is byte-for-byte the original payload

### Requirement: Listed topics are read from the log and all others from Kafka

A topic listed in `ludwig.messaging.db.consume` SHALL be read from the log, and every other topic
SHALL be read from Kafka. This SHALL hold per topic, including for a single listener that subscribes
to topics of both kinds. An existing listener, its container factory bean and its configuration SHALL
work unchanged.

#### Scenario: One listener, two transports

- **WHEN** a listener subscribes to `catalog.products` (listed) and `partner.orders` (not listed)
- **THEN** records appended to the log under `catalog.products` and records published to Kafka on
  `partner.orders` are both delivered to that listener

#### Scenario: The listener receives the same record shape

- **WHEN** a listener that reads the record's key, headers and value, and the envelope derived from
  them, receives a record from the log
- **THEN** the key, every header and the deserialized value equal what the producer wrote, and the
  record carries the log's topic, partition and offset

### Requirement: Every consumer group reads the whole log independently

Each consumer group SHALL keep its own position per (topic, partition). Every group subscribed to a
topic SHALL receive every record in it. A group's progress SHALL NOT affect any other group.

#### Scenario: Fan-out to two services

- **WHEN** groups `billing` and `search` both subscribe to `catalog.products` and one record is
  appended
- **THEN** the record is delivered once to `billing` and once to `search`

#### Scenario: A new group starts where offset reset says

- **WHEN** a group subscribes for the first time to a topic that already holds records
- **THEN** with `auto-offset-reset=earliest` it receives the existing records, and with `latest` it
  receives only records appended after it joined

### Requirement: Records with the same key are delivered in order to one consumer at a time

A record SHALL be assigned to a partition from its key with the same partitioning function Kafka's
default producer uses. Within one group, at most one consumer instance SHALL process a given
partition at a time. Records in that partition SHALL be delivered in offset order, and a record
SHALL NOT be delivered before every earlier record in its partition has been handled or
dead-lettered.

#### Scenario: Ordering across instances

- **WHEN** three instances of one service in group `billing` consume `catalog.products` and events
  `e1, e2, e3` with key `p-1` are appended in that order
- **THEN** group `billing` handles `e1`, `e2`, `e3` in that order, each on a single instance

#### Scenario: A failed instance's partitions are taken over

- **WHEN** the instance processing a partition stops mid-batch without committing
- **THEN** another instance of the same group resumes that partition from its last committed
  position, and redelivers the uncommitted records

### Requirement: A record is never skipped because writers committed out of order

A consumer SHALL NOT advance past a record that has not yet become visible. Two concurrent writers to
the same partition SHALL NOT be able to produce a committed record at an offset lower than one a
consumer has already passed.

#### Scenario: Concurrent appends

- **WHEN** two outbox pollers on different instances append to the same partition concurrently, and
  the later-started transaction commits first
- **THEN** every group eventually receives both records, and none is skipped

### Requirement: Delivery is at least once and advances only on success

A group's position SHALL advance past a record only after the listener returned normally, or after
the record was dead-lettered. A record whose handling threw SHALL be redelivered with the backoff and
attempt budget that `ludwig.messaging` resolves for that consumer. Once the budget is spent it SHALL
be dead-lettered. A deserialization failure, and an event version outside the consumer's accepted
range, SHALL be dead-lettered without retry.

#### Scenario: Retry, then dead letter

- **WHEN** a listener throws on every delivery of one record, and its resolved attempt budget is 4
- **THEN** the record is delivered exactly 4 times, with the resolved backoff between deliveries
- **AND** it is then dead-lettered, and the group's position moves past it

#### Scenario: Unparseable payload goes straight to the dead letter

- **WHEN** a record's payload cannot be deserialized to the listener's payload type
- **THEN** it is delivered zero times to the listener and dead-lettered once

#### Scenario: A failed dead-letter write does not advance

- **WHEN** a record's attempts are exhausted and appending its dead-letter row fails
- **THEN** the group's position does not advance past it, and `ludwig.messaging.dropped` is
  incremented

### Requirement: Consumer-side dedup and correlation apply to log-backed topics

The dedup filter and the correlation id handling configured for a consumer SHALL apply to records
read from the log exactly as they apply to records read from Kafka.

#### Scenario: A republished event is filtered

- **WHEN** dedup is enabled and the log holds two records with the same `ludwig-idempotency-key` for
  one group
- **THEN** the listener handles only the first

#### Scenario: Correlation id reaches the listener's logs

- **WHEN** a record carries `X-Correlation-Id: c-42`
- **THEN** the listener runs with correlation id `c-42` in its context

### Requirement: Unsupported consumer shapes are refused at startup

The application SHALL fail to start with a message naming the consumer and the topic when either
of these is configured:

- non-blocking retry topics on a consumer that reads a listed topic
- a pattern subscription on a consumer when the pattern could match a listed topic

#### Scenario: Retry topics on a log topic

- **WHEN** consumer `orders-ingress` enables non-blocking retry and listens on a listed topic
- **THEN** startup fails and names `orders-ingress` and the topic

### Requirement: Draining mode moves writes to Kafka without losing log records

When `ludwig.messaging.db.mode` is `draining`:

- writes SHALL go to Kafka, as if the starter were absent
- listed topics SHALL be read from both the log and Kafka
- the starter SHALL expose the number of unconsumed log records per (group, topic) as a metric

#### Scenario: Records written before the switch are still delivered

- **WHEN** a log topic holds 10 records that group `billing` has not consumed, and every service is
  switched to `draining`
- **THEN** group `billing` receives those 10 records from the log and receives new records from
  Kafka, and its log-lag metric reaches 0

### Requirement: The starter can be removed without touching any other code

Removing the starter's dependency and its `ludwig.messaging.db.*` properties SHALL leave every
producer, listener, container factory bean and outbox route working against Kafka with no edit. No
class outside the starter SHALL depend on the starter's classes, and the build SHALL fail when one
does.

#### Scenario: A service imports the starter's API

- **WHEN** a class in a service references a class from the starter's package
- **THEN** that service's architecture-rules test fails and names the class

#### Scenario: Removal

- **WHEN** the dependency is removed from a service that ran in `draining` mode
- **THEN** the service starts, publishes to Kafka and consumes from Kafka with no source change

### Requirement: The log schema installs safely from every service that shares the database

Every service carrying the starter SHALL be able to install the log schema against the shared
database, in any order and concurrently. No service SHALL fail because another one has already
created the schema.

#### Scenario: Second service starts against an existing schema

- **WHEN** service A has created the log tables and service B, with its own migration history,
  starts with the starter
- **THEN** service B starts and uses the existing tables

### Requirement: Old log records are purged

Records older than `ludwig.messaging.db.retention` SHALL be deleted on a schedule, whether or not
every group has consumed them. Before deletion, any record that some group has not yet consumed
SHALL be counted and logged per (group, topic).

#### Scenario: Purge reports what a slow group lost

- **WHEN** retention is 7 days, and group `search` has not consumed 3 records older than that
- **THEN** the purge deletes them and logs that 3 records of that topic were purged unconsumed for
  group `search`
