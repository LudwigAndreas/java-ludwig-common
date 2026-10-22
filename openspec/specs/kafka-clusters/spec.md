# kafka-clusters Specification

## Purpose
Lets one service read from and write to several Kafka clusters by name, each consumer and outbox route
choosing its cluster, while dedup, dead letters and metrics stay correct per cluster.
## Requirements
### Requirement: Clusters are named, and spring.kafka is the default one

A cluster SHALL be declared under `ludwig.kafka.clusters.<name>` with the property shape of
`spring.kafka.*`. Its values SHALL be applied on top of `spring.kafka.*`, so that an entry states only
what differs. `spring.kafka.*` alone SHALL be the cluster named `default`, and a service that declares
no cluster SHALL behave exactly as before this change.

#### Scenario: A cluster inherits what it does not override

- **WHEN** `spring.kafka.consumer.auto-offset-reset=earliest` and
  `ludwig.kafka.clusters.partner-a.bootstrap-servers=a:9093` are set
- **THEN** a consumer on `partner-a` connects to `a:9093` and starts from `earliest`

#### Scenario: No clusters declared

- **WHEN** a service declares no `ludwig.kafka.clusters` entry
- **THEN** every consumer, dead-letter producer and outbox route uses `spring.kafka.*`, as before

### Requirement: A consumer reads from exactly one cluster

A consumer SHALL read from the cluster named by `ludwig.messaging.consumers.<name>.cluster`, and from
`default` when none is named. Naming an undeclared cluster SHALL fail startup, naming the consumer and
the cluster.

#### Scenario: Two consumers, two clusters

- **WHEN** consumer `orders-ingress` names `partner-a`, consumer `settings-sync` names `partner-b`, and a
  record is published to each cluster's topic
- **THEN** each record is delivered to its own consumer only

#### Scenario: An undeclared cluster

- **WHEN** a consumer names cluster `partner-c` and no such cluster is declared
- **THEN** startup fails, naming the consumer and `partner-c`

### Requirement: A consumer's dead letters go to a configured cluster

A dead-lettered record SHALL be written to the cluster named by
`ludwig.messaging.consumers.<name>.dead-letter.cluster`. The value `source`, which is the default,
SHALL mean the cluster the record was read from. A default for every consumer SHALL be settable under
`ludwig.messaging.defaults.dead-letter.cluster`. The topic name, partition choice, counting and audit
SHALL be unchanged.

#### Scenario: Dead letter back to the source

- **WHEN** a consumer on `partner-a` with no dead-letter cluster configured exhausts its attempts on a
  record from `invoices`
- **THEN** the record is published to `invoices.dlt` on `partner-a`

#### Scenario: Dead letter to another cluster

- **WHEN** the same consumer sets `dead-letter.cluster=internal`
- **THEN** the record is published to `invoices.dlt` on `internal`, and nothing is written to `partner-a`

### Requirement: An outbox route writes to the cluster its transport names

The outbox SHALL provide a Kafka transport per declared cluster, named `kafka:<cluster>`. Plain `kafka`
SHALL remain the `default` cluster. A route whose transport names an undeclared cluster SHALL fail
startup, naming the route and the cluster.

#### Scenario: Two routes, two clusters

- **WHEN** route `orders` uses `kafka:internal` and route `invoices` uses `kafka:partner-a`
- **THEN** an `orders` event is published to `internal`, an `invoices` event to `partner-a`, and each
  outbox row is recorded with the transport it was published over

#### Scenario: Existing routes are unchanged

- **WHEN** a route keeps `transport: kafka`
- **THEN** its events are published to `spring.kafka.bootstrap-servers`, as before

### Requirement: Dedup does not confuse records from different clusters

A consumer-side dedup claim for a record read from a non-default cluster SHALL be scoped by that
cluster as well as by topic. Claims for records read from `default` SHALL keep their existing scope.

#### Scenario: Same topic, same coordinates, two clusters

- **WHEN** consumers on `partner-a` and `partner-b` each read a record with no idempotency key from a
  topic named `orders` at partition 0, offset 42
- **THEN** both records are delivered, and neither is discarded as a duplicate

#### Scenario: Default cluster claims are untouched

- **WHEN** a service upgrades with dedup claims already stored for consumers on `default`
- **THEN** a redelivered record on `default` is still recognised as a duplicate of its stored claim

### Requirement: An outbox record carries the publisher's correlation id

An outbox event published while a correlation id is in context SHALL be dispatched with that id under
messaging's correlation header name (`X-Correlation-Id` unless configured otherwise), on every cluster
and transport. A header with that name already set on the event SHALL be kept. An event published with
no correlation id in context SHALL carry none.

#### Scenario: A request's id reaches the record

- **WHEN** a request with correlation id `c-7` publishes an outbox event, and the poller later
  dispatches it over `kafka:partner-a`
- **THEN** the record on `partner-a` carries `X-Correlation-Id: c-7`

#### Scenario: An explicit header wins

- **WHEN** the event already sets `X-Correlation-Id: upstream-1` and the request's id is `c-7`
- **THEN** the record carries `X-Correlation-Id: upstream-1`

### Requirement: Messaging metrics tell clusters apart

Every `ludwig.messaging.*` meter SHALL carry a `cluster` tag naming the cluster the record was read from.

#### Scenario: Same topic on two clusters

- **WHEN** a record is dead-lettered from `orders` on `partner-a` and another from `orders` on `partner-b`
- **THEN** `ludwig.messaging.dead.lettered` has one series tagged `cluster=partner-a` and one tagged
  `cluster=partner-b`

### Requirement: Retry topics stay on the default cluster

A consumer that enables non-blocking retry topics and names a cluster other than `default` SHALL fail
startup, naming the consumer.

#### Scenario: Retry topics on a partner cluster

- **WHEN** consumer `orders-ingress` names `partner-a` and sets `retry.non-blocking=true`
- **THEN** startup fails and names `orders-ingress`

