# Seed fixtures

## Purpose
Defines how a service declares the data and messages it needs present before it can be exercised
locally, so that a local application or an integration test starts from a known state instead of
an empty database.

## Requirements

### Requirement: Fixtures run after migrations, in a declared order
Seed fixtures SHALL run after the service's database migrations have completed and before the
launcher reports the service ready. A fixture SHALL be able to declare that it runs after another,
and fixtures SHALL run in an order that satisfies every such declaration.

#### Scenario: One fixture depends on another
- **WHEN** a product fixture declares that it runs after a supplier fixture
- **THEN** the supplier rows exist when the product fixture runs

#### Scenario: Fixtures declare a cycle
- **WHEN** two fixtures each declare that they run after the other
- **THEN** start-up fails naming both fixtures

#### Scenario: A fixture fails
- **WHEN** a fixture throws
- **THEN** its own writes are rolled back, start-up fails naming the fixture, and no later fixture
  runs

### Requirement: The seeding mode is explicit
Seeding SHALL have three modes selected by `ludwig.local.fixtures.mode`: `reset` (empty every
table except the migration tool's, then seed), `keep` (seed only when the database holds no rows
from a previous run), and `none` (do nothing). The default SHALL be `reset`.

#### Scenario: A reused container holds a previous run's rows
- **WHEN** the local application starts in the default mode against a database that already holds
  rows
- **THEN** the rows are removed and the fixtures run once, so the start state is the same on every
  launch

#### Scenario: A developer wants to keep their data
- **WHEN** the local application starts with `ludwig.local.fixtures.mode=keep` against a database
  the previous run seeded and then changed
- **THEN** no table is emptied and no fixture runs

#### Scenario: Seeding is switched off
- **WHEN** the mode is `none`
- **THEN** no table is emptied, no fixture runs, and the start-up summary says so

### Requirement: A fixture writes through the service's own code
A fixture SHALL write through the service's repositories, its services or its own API, reached
from inside the running application, and SHALL NOT be a SQL script. A fixture that stops matching the schema therefore
fails to compile or fails at the first launch, instead of silently seeding stale columns.

#### Scenario: An entity gains a required column
- **WHEN** a migration adds a non-null column and the fixture is not updated
- **THEN** the next launch, and the next run of the module's integration tests, fails in that
  fixture

### Requirement: Fixtures are usable from an integration test
The same fixtures SHALL be runnable on demand from an integration test.

#### Scenario: A test seeds before each method
- **WHEN** an integration test empties the database and runs the service's fixtures before a test
  method
- **THEN** the rows the local application would start with are present

### Requirement: Messages can be seeded and duplicated
The module SHALL provide a way to publish seed messages to a topic and a way to deliver one message
more than once with the same key, headers and payload, so a consumer's handling of a redelivery can
be exercised.

#### Scenario: A message is delivered twice
- **WHEN** a test or fixture asks for a record to be delivered twice
- **THEN** the consumer receives two records with identical key, headers and payload at different
  offsets

#### Scenario: Kafka is not named
- **WHEN** a fixture publishes messages and the local application did not name Kafka
- **THEN** start-up fails with a message saying the fixture needs Kafka
