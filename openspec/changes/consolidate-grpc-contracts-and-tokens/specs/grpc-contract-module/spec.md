## Purpose

How a service publishes its gRPC contract as a module: one parent supplies everything a contract
module needs, so the module is its identity and its `.proto` files, and an incompletely declared
contract module fails the verification gate instead of failing silently later.

## ADDED Requirements

### Requirement: A contract module declares only its identity and its protocol files
A `<service>-grpc-api` module SHALL inherit the platform's contract parent and SHALL need no
dependency, plugin or property of its own to generate, compile and publish its messages and stubs.
It SHALL contain no hand-written Java.

#### Scenario: A minimal contract module is built
- **WHEN** a module's POM declares only the contract parent, its `relativePath`, its `artifactId`,
  a name and a description, and the module holds one conforming `.proto`
- **THEN** `mvn -pl :<service>-grpc-api -am verify` produces a jar with the generated messages and
  the service stub, and `scripts/check_protos.sh` passes

#### Scenario: A contract returns a long-running operation
- **WHEN** a contract's `.proto` imports `ludwig/operation/v1/operation.proto` and returns
  `ludwig.operation.v1.Operation`
- **THEN** the module builds with no dependency declared in its own POM

#### Scenario: Java source is added to a contract module
- **WHEN** a `.java` file is placed under a `*-grpc-api` module's `src/main/java`
- **THEN** `scripts/manifest.sh layout` exits non-zero naming the module, because a contract module
  is generated code and nothing else

### Requirement: An incompletely declared contract module fails the gate
A `*-grpc-api` module SHALL inherit the contract parent and SHALL be registered in the reactor, in
`ludwig-bom` and in the coverage aggregate; a module missing any of these SHALL fail
`scripts/manifest.sh layout`, naming the module and what is missing.

#### Scenario: A contract module has another parent
- **WHEN** a `*-grpc-api` module declares `common` as its parent
- **THEN** `scripts/manifest.sh layout` exits non-zero naming the module and the contract parent

#### Scenario: A contract module is missing from the BOM
- **WHEN** a `*-grpc-api` module is in the reactor but has no entry in `ludwig-bom`
- **THEN** `scripts/manifest.sh layout` exits non-zero naming the module and `ludwig-bom`

#### Scenario: A contract module is missing from the coverage aggregate
- **WHEN** a `*-grpc-api` module is in the reactor but not a dependency of `jacoco-aggregate`
- **THEN** `scripts/manifest.sh layout` exits non-zero naming the module and `jacoco-aggregate`,
  before any build has run

#### Scenario: A contract module directory is not in the reactor
- **WHEN** `sources/<service>-grpc-api/pom.xml` exists and the root POM has no `<module>` for it
- **THEN** `scripts/manifest.sh layout` exits non-zero naming the directory

#### Scenario: A complete contract module is checked
- **WHEN** a `*-grpc-api` module inherits the contract parent and is registered in all three places
- **THEN** `scripts/manifest.sh layout` exits zero
