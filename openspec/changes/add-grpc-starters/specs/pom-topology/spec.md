## ADDED Requirements

### Requirement: gRPC and protobuf versions live in the BOM and move together
The gRPC and protobuf runtime versions SHALL be declared in `ludwig-bom`. The versions of the
protobuf compiler and the gRPC code generator, which are plugin configuration and cannot be read
from a BOM, SHALL be declared in the root POM and SHALL equal the runtime versions; the
verification gate SHALL fail when either pair differs.

#### Scenario: The protobuf runtime version is raised without the compiler
- **WHEN** `protobuf.version` in `ludwig-bom` is changed and `protoc.version` in the root POM is not
- **THEN** `scripts/check_protos.sh` exits non-zero and names both properties and both values

#### Scenario: The gRPC runtime version is raised without the generator
- **WHEN** `grpc.version` in `ludwig-bom` is changed and `protoc-gen-grpc-java.version` in the root
  POM is not
- **THEN** `scripts/check_protos.sh` exits non-zero

#### Scenario: A module pins its own gRPC version
- **WHEN** a module POM declares a version on a gRPC or protobuf dependency
- **THEN** it is rejected in favour of the version managed by `ludwig-bom`

### Requirement: Code generation is managed in the root POM and opted into per module
The protobuf code-generation plugin SHALL have its version and configuration in the root POM's
plugin management, SHALL run only in a module that declares it, and SHALL NOT be added to
`ludwig-service-parent`.

#### Scenario: A module with no proto files is built
- **WHEN** a library that does not declare the plugin is built
- **THEN** no code generation runs and no protobuf compiler is resolved for it

#### Scenario: A contract module is added
- **WHEN** a new `-grpc-api` module declares the plugin with no version and no configuration
- **THEN** its messages and stubs are generated at build time

#### Scenario: A clean clone is built
- **WHEN** the reactor is built on a machine with no protobuf compiler installed
- **THEN** the build succeeds, because the compiler is resolved as a build dependency

### Requirement: Generated protobuf code is outside the style and coverage gates
Code generated from `.proto` files SHALL NOT be checked by Checkstyle and SHALL be excluded from
coverage measurement and static analysis, and a module that contains only generated code SHALL
still appear in the aggregate coverage report.

#### Scenario: A contract module is validated
- **WHEN** `mvn validate` runs on a module whose only Java is generated
- **THEN** Checkstyle reports no violation in generated code

#### Scenario: The aggregate report is checked
- **WHEN** `scripts/check_aggregate_report.py` runs after a full build
- **THEN** it exits 0 with the gRPC modules present in the report
