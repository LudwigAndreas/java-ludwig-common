## RENAMED Requirements

- FROM: `### Requirement: Three POMs with disjoint responsibilities`
- TO: `### Requirement: Four POMs with disjoint responsibilities`

## MODIFIED Requirements

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
