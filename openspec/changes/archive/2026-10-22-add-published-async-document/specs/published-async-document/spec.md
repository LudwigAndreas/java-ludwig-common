## Purpose

A service's Kafka surface is a generated AsyncAPI document committed to the tree and published to Nexus,
so that a change to what it sends or expects is visible in a diff and other teams can fetch it, and the
build fails when the committed description no longer matches the service.

## ADDED Requirements

### Requirement: A service with a Kafka surface publishes its AsyncAPI document into the tree

Each service that consumes or produces Kafka messages SHALL have a current AsyncAPI document committed at
`docs/api/<artifactId>.asyncapi.yaml`, produced from the service's real application context and not
written or edited by hand.

#### Scenario: A reviewer reads a pull request that changes a payload

- **WHEN** a change adds, removes or retypes a field of a payload the service consumes or produces
- **THEN** the committed AsyncAPI document changes in the same commit

#### Scenario: The document is edited by hand

- **WHEN** somebody edits `docs/api/<artifactId>.asyncapi.yaml` directly
- **THEN** the next `verify` of that service fails, because the generated document no longer matches

### Requirement: The build fails on any divergence between the code and the committed AsyncAPI document

The comparison SHALL run during `verify` for the owning service, compare the canonicalized generated
document against the committed bytes, report the command that refreshes it, and never rewrite the
committed copy during an ordinary build. Canonicalization SHALL sort every object's members and replace
each server's `host` with the server's name, so that no address is committed and the document stays valid
AsyncAPI.

#### Scenario: A listener is added without refreshing the document

- **WHEN** a `@KafkaListener` is added and the service is built with `verify`
- **THEN** the build fails naming the document, and states the refresh command

#### Scenario: Generation order changes

- **WHEN** the same context is documented twice with its beans discovered in a different order
- **THEN** the canonicalized documents are byte-identical

### Requirement: Every message carries the platform envelope

Every message in the document SHALL declare the envelope headers the platform writes and reads
(`ludwig-event-type`, `ludwig-event-version`, `ludwig-aggregate-type`, `ludwig-aggregate-id`,
`ludwig-idempotency-key`, `ludwig-trace-id`, `ludwig-produced-at`, and the correlation header), with the
same names `MessageHeaders` defines.

#### Scenario: A consumed message's headers

- **WHEN** a reader looks up any message in a generated document
- **THEN** its headers schema lists every envelope header under its wire name

### Requirement: Consumers are documented with their cluster and dead-letter channel

Every listener SHALL appear as a receive operation on its topic, with the payload schema of the type it
receives, the cluster it reads from, and a channel for its dead-letter topic on the cluster dead letters
go to. A text-payload listener SHALL appear with a string payload.

#### Scenario: A typed consumer on a partner cluster

- **WHEN** consumer `orders-ingress` reads `partner.orders` as `OrderPlaced` from cluster `partner-a` and
  dead-letters to `internal`
- **THEN** the document has a receive operation on `partner.orders` bound to server `partner-a` with the
  `OrderPlaced` schema, and a channel `partner.orders.dlt` bound to server `internal`

### Requirement: Outbox producers are documented from their routes and declared payloads

Every event type declared in `OutboxEventTypes` and matched by an outbox route over a Kafka transport
SHALL appear as a send operation on the route's destination, bound to the route's cluster, with the
declared payload's schema and the event type as the message name.

#### Scenario: A declared event on a partner route

- **WHEN** `InvoiceIssued` is declared with payload `InvoicePayload` and routed over `kafka:partner-a` to
  `invoices`
- **THEN** the document has a send operation on `invoices` bound to server `partner-a` whose message is
  named `InvoiceIssued` with the `InvoicePayload` schema

#### Scenario: An event the code publishes down a named route

- **WHEN** `NotifyProductCreated` is declared with route `product-notifications`, a REST route, while the
  default route is Kafka
- **THEN** it does not appear in the AsyncAPI document, and with `require-declared=true` publishing it
  with any other route fails

#### Scenario: A declared event with no Kafka route

- **WHEN** an event type is declared but routed over REST
- **THEN** it does not appear in the AsyncAPI document

### Requirement: An undeclared event type can be refused at publish time

When `ludwig.outbox.events.require-declared` is `true`, publishing an event whose type is not declared,
whose payload is not an instance of the declared class, or whose explicit route differs from the declared
one, SHALL fail with an exception naming the event type. When it is `false`, the default, publishing SHALL behave as before.

#### Scenario: A test publishes an undeclared event

- **WHEN** `require-declared=true` and code publishes event type `ProductArchived`, which is not declared
- **THEN** the publish throws, naming `ProductArchived`, and no outbox row is written

#### Scenario: Production default

- **WHEN** `require-declared` is not set and an undeclared event type is published
- **THEN** it is written to the outbox as before

### Requirement: Clusters are named servers without addresses

Each Kafka cluster a service uses SHALL appear as an AsyncAPI server named after the cluster, with
protocol `kafka`. Its `host`, which AsyncAPI requires, SHALL be the cluster name, never a host or
bootstrap address, so that the committed document is valid AsyncAPI and names no address.

#### Scenario: A document generated in a test

- **WHEN** the document is generated against test brokers on random ports
- **THEN** every server in the committed document has its own name as its host, and none contains a
  real host or port

### Requirement: The AsyncAPI document is a description, not a compatibility promise

The build SHALL NOT fail because a change to the document is backwards-incompatible for a consumer.

#### Scenario: A payload field is removed

- **WHEN** a payload field is removed and the document is refreshed in the same commit
- **THEN** the build passes and the removal is visible in the diff
