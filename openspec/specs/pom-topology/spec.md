# POM topology and the version single-source

## Purpose
Three POMs divide the build between them, and every module belongs to exactly one tier. Getting
this wrong is the single most common way a change to this reactor breaks modules it never touched.
## Requirements
### Requirement: Module parentage follows the module's role
A **library** module SHALL be parented by the reactor root `common` and SHALL import `ludwig-bom`
(`type=pom`, `scope=import`). A **service** module SHALL be parented by `ludwig-service-parent`. A
**gRPC contract** module SHALL be parented by `ludwig-grpc-api-parent`, which imports `ludwig-bom`
for it.

#### Scenario: A new starter module is added
- **WHEN** a change adds a Spring Boot starter or plain library
- **THEN** its POM declares `common` as its parent and imports `ludwig-bom` in
  `dependencyManagement`

#### Scenario: A new runnable service is added
- **WHEN** a change adds a runnable service alongside `crud-service-example` and
  `notification-service`
- **THEN** its POM declares `ludwig-service-parent` as its parent, so that it inherits the
  coverage gate, the surefire/failsafe split and the annotation-processor ordering rather than
  restating them

#### Scenario: A new gRPC contract module is added
- **WHEN** a change adds `<service>-grpc-api`
- **THEN** its POM declares `ludwig-grpc-api-parent` as its parent and declares no dependency,
  plugin or `dependencyManagement` of its own

### Requirement: The root POM never imports the BOM
`pom.xml` SHALL NOT import `ludwig-bom`. The root POM is the BOM's parent, so importing it would
be circular.

#### Scenario: A change tries to manage a version from the root POM by importing the BOM
- **WHEN** the root POM adds `ludwig-bom` to its `dependencyManagement` with `scope=import`
- **THEN** the reactor fails to resolve, because `ludwig-bom`'s parent is the POM importing it

### Requirement: The version lives in exactly one place
The project version SHALL be expressed as `${revision}` in every POM and SHALL NOT be written as a
literal in any of them. `flatten-maven-plugin` resolves it on install and deploy.

What *sets* `${revision}` is the git history, computed by GitVersion and passed as
`-Drevision=<computed>` on the command line; see the `release-versioning` capability for the mapping
from history to version. `.mvn/maven.config` retains a `-Drevision` value as the fallback for a
checkout with no tags and for a build with no GitVersion available, and a command-line user property
beats it. It is therefore no longer the source of the released version and SHALL NOT be edited to cut
one.

#### Scenario: A module hard-codes its version
- **WHEN** a POM declares a literal version instead of `${revision}`
- **THEN** that is a defect: the module stops moving with the reactor, and a release produces two
  different versions from one build

#### Scenario: A release is cut
- **WHEN** `master` is tagged `v1.2.0` and the pipeline builds that commit with
  `mvn -Drevision=1.2.0 -Prelease -Pci deploy`, where the value of `-Drevision` came from GitVersion
  rather than from a person
- **THEN** `-Prelease` only *enforces* the release preconditions — no SNAPSHOT dependencies, a
  concrete revision, a changelog entry for that version in both locales — and does not itself set the
  version; the version came from the tag by way of `-Drevision`

#### Scenario: A local build with no version on the command line
- **WHEN** a developer runs `mvn clean install` with no `-Drevision`
- **THEN** the value in `.mvn/maven.config` applies, and the build produces a locally installable
  SNAPSHOT — which is what that file is now for, and the only thing it is for

#### Scenario: A change edits the version file to release
- **WHEN** a change edits `-Drevision` in `.mvn/maven.config` in order to publish a version
- **THEN** that is a defect against the `release-versioning` capability: the release is the tag, and a
  value in that file which disagrees with the history misleads the next local build

### Requirement: The Spring Boot version moves in two places at once
`spring-boot.version` appears as a property in the root POM and as a literal in
`ludwig-service-parent`'s `<parent>` element, because Maven does not interpolate a parent
version. A change to the Boot line SHALL update both in the same commit.

#### Scenario: The Spring Boot line is upgraded
- **WHEN** a change upgrades Spring Boot
- **THEN** both the root POM property and the literal in `ludwig-service-parent`'s `<parent>` are
  changed in the same commit, or libraries and services silently build against different Boot
  lines

### Requirement: JUnit stays unpinned
JUnit SHALL NOT be pinned in `ludwig-bom` or in any module POM. It comes from
`spring-boot-dependencies`, so it moves with the Boot line rather than against it.

#### Scenario: A module pins a JUnit version
- **WHEN** a POM declares an explicit JUnit version
- **THEN** that is a defect, because it can diverge from the Boot line's managed version and
  produce a classpath with two JUnit platforms on it

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

### Requirement: Four POMs with disjoint responsibilities
The repository SHALL keep exactly four structural POMs with disjoint responsibilities: `pom.xml`
(the reactor and the libraries' parent), `ludwig-bom` (third-party versions only),
`ludwig-service-parent` (the services' build decisions) and `ludwig-grpc-api-parent` (what every
gRPC contract module needs and nothing else).

| POM | Owns |
|---|---|
| `pom.xml` (root, artifact `common`) | The reactor, and the parent of the **library** modules and of `ludwig-grpc-api-parent`. Imports `spring-boot-dependencies` so libraries do not bake in a Boot line. |
| `ludwig-bom` | Third-party versions and nothing else. Published parentless. |
| `ludwig-service-parent` | Every build decision services inherit: compiler and annotation processors in Lombok → MapStruct → QueryDSL order, the surefire/failsafe split, the enforced JaCoCo gate (70% instruction / 60% branch), enforcer, Checkstyle, jib. Its own parent is `spring-boot-starter-parent`. |
| `ludwig-grpc-api-parent` | A contract module's dependencies and its protobuf generation. Parented by `common`, importing `ludwig-bom`; no version of its own and no build decision a library does not already have. |

#### Scenario: A third-party version is added
- **WHEN** a change needs to pin a third-party dependency version
- **THEN** the version is declared in `build/ludwig-bom/pom.xml`, and not in the root POM

#### Scenario: A plugin version or build configuration is added
- **WHEN** a change needs to configure a build plugin
- **THEN** it goes in the root POM if it applies to libraries, in `ludwig-service-parent` if it
  applies to services, in `ludwig-grpc-api-parent` only if it applies to contract modules alone,
  and never in `ludwig-bom`

