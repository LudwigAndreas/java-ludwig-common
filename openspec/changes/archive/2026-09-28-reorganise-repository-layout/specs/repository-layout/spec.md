## ADDED Requirements

### Requirement: Every module lives in the directory its role dictates
Each module directory SHALL sit exactly one level below the repository root, under `build/`,
`services/` or `sources/` according to its role. `build/` holds what other modules inherit, import
or are checked by: `ludwig-bom`, `ludwig-service-parent`, `checkstyle-rules`, `architecture-rules`.
`services/` holds modules parented by `ludwig-service-parent`. `sources/` holds every library,
starter and test-support module.

#### Scenario: A new starter is added
- **WHEN** a change adds a Spring Boot starter
- **THEN** its directory is `sources/<name>`, and the root POM's `<module>` entry is
  `sources/<name>`

#### Scenario: A module is placed in the wrong directory
- **WHEN** a service is created outside `services/`, or a library or starter outside `sources/`, or
  the BOM, the service parent or a rules jar outside `build/`
- **THEN** `scripts/manifest.sh --check-layout` exits non-zero naming the module, its role and the
  directory it is in, and the verification gate fails. The rule is checked there rather than in
  ArchUnit or Checkstyle because a directory's position is a fact about the filesystem, which
  bytecode analysis and source-text analysis both cannot see

#### Scenario: A module directory is nested more deeply
- **WHEN** a module is placed at `sources/starters/<name>`
- **THEN** it is refused. The layout is deliberately two levels and not three: splitting `sources/`
  by kind makes "is this a library or a starter" a judgement call on every new module

### Requirement: A module parented by the reactor root declares its relativePath explicitly
Every module POM whose parent is `common` SHALL declare
`<relativePath>../../pom.xml</relativePath>`. Maven's default is `../pom.xml`, which from a module
one level deeper resolves to a directory that holds no POM.

#### Scenario: A new module omits relativePath
- **WHEN** a module POM declares `common` as its parent with no `<relativePath>`
- **THEN** Maven does not fail. It resolves the parent from the local repository instead, and the
  module builds against whatever `common` was last installed there — so plugin configuration, the
  Checkstyle binding and the annotation-processor ordering silently revert. This is the failure
  mode the explicit path exists to prevent, and it is why the rule is stated rather than left to
  the default

#### Scenario: The reactor is built with no ludwig artifacts installed
- **WHEN** `~/.m2/repository/ru/ludwigandreas` is removed and `mvn clean install` is run from the
  reactor root
- **THEN** the build succeeds, which is the proof that every parent resolved in-reactor rather than
  from a stale local artifact

### Requirement: Module selection is by artifactId, not by path
Commands that select modules — the verification gate, CI image builds — SHALL use `mvn -pl
:<artifactId>` rather than `mvn -pl <path>`.

#### Scenario: The layout changes again
- **WHEN** a future change moves a module between directories
- **THEN** every gate and CI command keeps working unchanged, because none of them names a
  directory. Path-based selection is precisely what this change had to repair across the
  Jenkinsfile and the gate script

#### Scenario: A developer runs the gate for one module
- **WHEN** `scripts/manifest.sh module <path>` prints the gate
- **THEN** the commands it prints use `-pl :<artifactId>`, so they can be pasted from anywhere in
  the repository

### Requirement: Artifact coordinates are unaffected by layout
Moving a module SHALL NOT change its `groupId`, `artifactId` or version, and SHALL NOT change any
in-repo dependency edge.

#### Scenario: The manifest is regenerated after the move
- **WHEN** `scripts/manifest.sh build` runs after the directories change
- **THEN** every module's `inRepoDependencies` and `inRepoDependents` are unchanged, because Maven
  builds the reactor graph from coordinates and not from directory positions. A diff showing an
  altered edge means the move disturbed something and is a defect, not a consequence
