## MODIFIED Requirements

### Requirement: Every module lives in the directory its role dictates
Each module directory SHALL sit exactly one level below the repository root, under `build/`,
`services/` or `sources/` according to its role. `build/` holds what other modules inherit, import
or are checked by: `ludwig-bom`, `ludwig-service-parent`, `ludwig-grpc-api-parent`,
`checkstyle-rules`, `architecture-rules`. `services/` holds modules parented by
`ludwig-service-parent`. `sources/` holds every library, starter, gRPC contract and test-support
module.

#### Scenario: A new starter is added
- **WHEN** a change adds a Spring Boot starter
- **THEN** its directory is `sources/<name>`, and the root POM's `<module>` entry is
  `sources/<name>`

#### Scenario: A new gRPC contract module is added
- **WHEN** a change adds `<service>-grpc-api`
- **THEN** its directory is `sources/<service>-grpc-api`, and its role is a contract, distinct from
  a library, so that the contract checks apply to it

#### Scenario: A module is placed in the wrong directory
- **WHEN** a service is created outside `services/`, or a library, starter or contract module
  outside `sources/`, or the BOM, a parent POM or a rules jar outside `build/`
- **THEN** `scripts/manifest.sh layout` exits non-zero naming the module, its role and the
  directory it is in, and the verification gate fails. The rule is checked there rather than in
  ArchUnit or Checkstyle because a directory's position is a fact about the filesystem, which
  bytecode analysis and source-text analysis both cannot see

#### Scenario: A module directory is nested more deeply
- **WHEN** a module is placed at `sources/starters/<name>`
- **THEN** it is refused. The layout is deliberately two levels and not three: splitting `sources/`
  by kind makes "is this a library or a starter" a judgement call on every new module

### Requirement: A module parented by the reactor root declares its relativePath explicitly
Every module POM whose parent is built in this reactor SHALL declare its `<relativePath>` to that
parent: `../../pom.xml` for `common`, and `../../build/ludwig-grpc-api-parent/pom.xml` for the
contract parent. Maven's default is `../pom.xml`, which from a module one level deeper resolves to a
directory that holds no POM.

#### Scenario: A new module omits relativePath
- **WHEN** a module POM declares `common` or the contract parent as its parent with no
  `<relativePath>`
- **THEN** Maven does not fail. It resolves the parent from the local repository instead, and the
  module builds against whatever was last installed there — so plugin configuration, the
  Checkstyle binding and the annotation-processor ordering silently revert. This is the failure
  mode the explicit path exists to prevent, and it is why the rule is stated rather than left to
  the default

#### Scenario: The reactor is built with no ludwig artifacts installed
- **WHEN** `~/.m2/repository/ru/ludwigandreas` is removed and `mvn clean install` is run from the
  reactor root
- **THEN** the build succeeds, which is the proof that every parent resolved in-reactor rather than
  from a stale local artifact
