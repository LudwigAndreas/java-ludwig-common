## ADDED Requirements

### Requirement: gRPC structure is owned by ArchUnit as one rule group
The shared architecture rules SHALL include a gRPC rule group, on by default and vacuous for a
module with no gRPC type, that fails the build when a channel or server is built outside the
platform's two gRPC starters, when a gRPC service implementation lacks the platform stereotype, and
when a stereotyped service carries a proxy-based security or transaction annotation or depends
directly on a repository.

#### Scenario: A service builds its own channel
- **WHEN** a class outside the gRPC starters calls a gRPC channel builder
- **THEN** the gRPC rule group fails and names the class

#### Scenario: A gRPC service is annotated for method security
- **WHEN** a stereotyped gRPC service declares `@PreAuthorize` or `@Transactional` on a method
- **THEN** the gRPC rule group fails with a message that the annotation is not applied on the gRPC
  call path

#### Scenario: A module has no gRPC type
- **WHEN** the rule group analyses a module that references no gRPC type
- **THEN** it passes

#### Scenario: Each rule is shown to fail
- **WHEN** the architecture-rules module's own tests run
- **THEN** each gRPC rule is asserted to fire on a deliberately violating fixture

### Requirement: Protobuf conventions are owned by a gate script
Conventions about the content and placement of `.proto` files SHALL be enforced by a script the
verification gate runs, and SHALL NOT be written as ArchUnit or Checkstyle rules, because a `.proto`
is a resource that neither bytecode nor Java source analysis can see.

#### Scenario: The gate is run
- **WHEN** `scripts/gate.sh` runs for any module
- **THEN** it runs `scripts/check_protos.sh` and fails if that script fails

#### Scenario: A protobuf convention is proposed as a Checkstyle rule
- **WHEN** a change proposes checking a `.proto` option with Checkstyle
- **THEN** it is rejected in favour of the gate script

### Requirement: The gRPC area's unmechanisable rules are recorded, not dropped
The conventions in this area that no build can check SHALL be listed in the architecture-rules
README in both locales and stated at the point of each rule: wire compatibility of a proto change,
truthfulness of a declared idempotency level, that a client's target resolves to every server,
that a client's keepalive is compatible with its server's, and that a call between two platform
services uses gRPC.

#### Scenario: The unmechanisable set is enumerated
- **WHEN** a reader opens the architecture-rules README
- **THEN** it lists those five conventions under the gRPC rule group with the reason each cannot be
  checked

#### Scenario: A vacuous internal-call rule is proposed
- **WHEN** a change proposes an ArchUnit rule that an internal call must use gRPC
- **THEN** it is rejected, because whether a call is internal is not a fact bytecode carries
