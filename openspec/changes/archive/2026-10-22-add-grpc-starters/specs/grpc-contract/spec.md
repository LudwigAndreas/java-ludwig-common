## Purpose

What crosses the wire between two platform services over gRPC, independent of who wrote either
side: the metadata names, the relation between a platform problem and a gRPC status, the shape a
protobuf contract must have, and the build-time check that keeps a contract in that shape.

## ADDED Requirements

### Requirement: A contract is published as its own module
A service's gRPC contract SHALL be published as a library module named `<service>-grpc-api` that
contains its `.proto` files and the code generated from them and nothing else. A `.proto` file
SHALL NOT exist anywhere else in the reactor except the platform's own shared protos and test-only
protos under `src/test/proto`.

#### Scenario: A caller needs a service's messages
- **WHEN** a module calls another service over gRPC
- **THEN** it depends on that service's `<service>-grpc-api` artifact and declares no copy of the
  messages itself

#### Scenario: A proto file is added to a service module
- **WHEN** a `.proto` file is placed under `src/main` of a module that is not a `-grpc-api` module
  and is not the platform's shared contract module
- **THEN** `scripts/check_protos.sh` exits non-zero and names the file

### Requirement: A contract follows the platform's protobuf conventions
Every `.proto` in a contract module SHALL declare a versioned package `ludwig.<service>.v<N>`, a
`java_package` of `ru.ludwigandreas.<service>.grpc.v<N>` and `java_multiple_files = true`.

#### Scenario: A proto omits the version from its package
- **WHEN** a contract proto declares `package ludwig.billing;`
- **THEN** `scripts/check_protos.sh` exits non-zero and names the file and the expected form

#### Scenario: A conforming proto is checked
- **WHEN** every proto in the reactor follows the conventions
- **THEN** `scripts/check_protos.sh` exits 0

### Requirement: Every method declares whether a repeat is safe
Every `rpc` in a contract SHALL declare `idempotency_level` explicitly. `IDEMPOTENCY_UNKNOWN` is a
valid declaration; an absent one is not.

#### Scenario: A method has no declared idempotency level
- **WHEN** a contract proto contains an `rpc` with no `idempotency_level` option
- **THEN** `scripts/check_protos.sh` exits non-zero and names the service and the method

#### Scenario: A method is declared not safe to repeat
- **WHEN** an `rpc` declares `idempotency_level = IDEMPOTENCY_UNKNOWN`
- **THEN** the check passes

### Requirement: Metadata keys are the platform's HTTP header names in lower case
The correlation id, idempotency key, caller locale, caller zone and retry-after SHALL travel as
gRPC metadata under the lower-cased name of the HTTP header that carries the same value, and each
key SHALL be declared in exactly one place.

#### Scenario: An idempotency key is sent over gRPC
- **WHEN** a client attaches an idempotency key to a call
- **THEN** the server receives it under the metadata key `idempotency-key`

#### Scenario: A module needs a platform metadata key
- **WHEN** a module reads or writes one of these values on a gRPC call
- **THEN** it uses the shared key constant and declares no key string of its own

### Requirement: A platform problem and a gRPC status are two renderings of one fact
A problem SHALL map to exactly one gRPC status code, the mapping SHALL be defined for every problem
status the platform declares, and the status SHALL carry enough detail to reconstruct the problem:
its code, its original HTTP status, its type, the correlation id, any field violations, the
localized detail and any retry-after.

#### Scenario: A not-found problem is rendered
- **WHEN** a handler fails with an exception the platform maps to a 404 problem with code
  `product-not-found`
- **THEN** the caller receives status `NOT_FOUND` whose error details carry the reason
  `product-not-found` and the original status 404

#### Scenario: A validation failure is rendered
- **WHEN** a handler fails with a problem that carries field violations
- **THEN** the caller receives `INVALID_ARGUMENT` with one field violation per violated field

#### Scenario: A status is decoded by the caller
- **WHEN** a client receives a failure status produced by a platform server
- **THEN** the failure the caller's code sees carries a problem document with the same code, status
  and violations the server rendered

#### Scenario: A problem status is added to the platform without a gRPC mapping
- **WHEN** a new problem status is declared and no gRPC code is assigned to it
- **THEN** the shared contract module's build fails

### Requirement: The shared contract module has no transport
The module that holds the shared keys, codec and messages SHALL NOT depend on any gRPC transport
implementation.

#### Scenario: The codec is used without a server or a channel
- **WHEN** a test or tool depends on the shared contract module alone
- **THEN** it can encode and decode a status with no network transport on its classpath

#### Scenario: A transport type is imported into the shared module
- **WHEN** a class in the shared contract module references a Netty or channel-builder type
- **THEN** that module's build fails
