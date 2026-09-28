# POM topology and the version single-source

## Purpose
Three POMs divide the build between them, and every module belongs to exactly one tier. Getting
this wrong is the single most common way a change to this reactor breaks modules it never touched.

## Requirements

### Requirement: Three POMs with disjoint responsibilities
The repository SHALL keep exactly three structural POMs with disjoint responsibilities:
`pom.xml` (the reactor and the libraries' parent), `ludwig-bom` (third-party versions only), and
`ludwig-service-parent` (the services' build decisions).

| POM | Owns |
|---|---|
| `pom.xml` (root, artifact `common`) | The reactor, and the parent of the **library** modules only. Imports `spring-boot-dependencies` so libraries do not bake in a Boot line. |
| `ludwig-bom` | Third-party versions and nothing else. Published parentless. |
| `ludwig-service-parent` | Every build decision services inherit: compiler and annotation processors in Lombok → MapStruct → QueryDSL order, the surefire/failsafe split, the enforced JaCoCo gate (70% instruction / 60% branch), enforcer, Checkstyle, jib. Its own parent is `spring-boot-starter-parent`. |

#### Scenario: A third-party version is added
- **WHEN** a change needs to pin a third-party dependency version
- **THEN** the version is declared in `build/ludwig-bom/pom.xml`, and not in the root POM

#### Scenario: A plugin version or build configuration is added
- **WHEN** a change needs to configure a build plugin
- **THEN** it goes in the root POM if it applies to libraries, or in `ludwig-service-parent` if it
  applies to services, and never in `ludwig-bom`

### Requirement: Module parentage follows the module's role
A **library** module SHALL be parented by the reactor root `common` and SHALL import `ludwig-bom`
(`type=pom`, `scope=import`). A **service** module SHALL be parented by `ludwig-service-parent`.

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

### Requirement: The root POM never imports the BOM
`pom.xml` SHALL NOT import `ludwig-bom`. The root POM is the BOM's parent, so importing it would
be circular.

#### Scenario: A change tries to manage a version from the root POM by importing the BOM
- **WHEN** the root POM adds `ludwig-bom` to its `dependencyManagement` with `scope=import`
- **THEN** the reactor fails to resolve, because `ludwig-bom`'s parent is the POM importing it

### Requirement: The version lives in exactly one place
The project version SHALL be expressed as `${revision}` in every POM and set only by
`-Drevision=…` in `.mvn/maven.config`. `flatten-maven-plugin` resolves it on install and deploy.

#### Scenario: A module hard-codes its version
- **WHEN** a POM declares a literal version instead of `${revision}`
- **THEN** that is a defect: the module stops moving with the reactor, and a release produces two
  different versions from one build

#### Scenario: A release is cut
- **WHEN** a release is performed with `mvn -Drevision=1.2.0 -Prelease -Pci deploy`
- **THEN** `-Prelease` only *enforces* the release preconditions — no SNAPSHOT dependencies, a
  concrete revision — and does not itself set the version; the version came from `-Drevision`

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
