# Test layout and naming

## Purpose
In this reactor a test's **name** decides whether it runs at all. A misnamed integration test is
not a failing test — it is a test that never executes and never reports.

## Requirements

### Requirement: Tests live in one of three directories per module
Tests SHALL live under `src/test/java/ru/ludwigandreas/<module>/{unit,integration,architecture}`.

#### Scenario: A new test is added
- **WHEN** a change adds a test
- **THEN** it goes in `unit`, `integration` or `architecture` according to what it is, and the
  module's `testDirs` entry in `project-index.json` reflects which of the three exist

### Requirement: Integration tests are named so that failsafe runs them
Surefire SHALL run unit tests and SHALL exclude `*IT.java` and `*IntegrationTest.java`; failsafe
SHALL run those at `verify`. An integration test therefore SHALL be named `…IT` or
`…IntegrationTest`.

#### Scenario: An integration test is given a name matching neither pattern
- **WHEN** an integration test is called, for example, `FooIntegrationTests` or `FooTest` while
  needing a container
- **THEN** it is run by surefire at `test` rather than by failsafe at `verify` — so it either
  fails for want of its fixture or, worse, passes having tested nothing

#### Scenario: A change is verified with `mvn test`
- **WHEN** a change is checked with `mvn test` alone
- **THEN** no integration test has run. Verification requires `mvn -pl <module> -am verify`

### Requirement: The verification gate includes every in-repo dependent
Verifying a change to a module SHALL run `mvn -q validate`, then
`mvn -pl <module> -am verify`, then `mvn -pl <dependent> -am verify` for every in-repo dependent
of that module.

#### Scenario: A library with dependents is changed
- **WHEN** a change touches `job-core`, which seven modules depend on
- **THEN** the gate runs `job-core` and each of those seven, because `-am` builds a module's
  dependencies and not its dependents — the direction that matters for a breaking change

#### Scenario: A BOM or parent POM is changed
- **WHEN** a change touches `ludwig-bom` or `ludwig-service-parent`
- **THEN** the gate is `mvn clean install` over the whole reactor, because every module inherits
  or imports them and there is no narrower gate

### Requirement: A service enables the shared architecture rules by subclassing
A service SHALL enable `architecture-rules` with a subclass of `ArchitectureRulesTest` annotated
`@AnalyzeArchitecture(...)`, plus `src/test/resources/architecture-rules.properties` naming the
service-specific base types.

#### Scenario: Architecture rules are enabled for a service
- **WHEN** a service adds the subclass and the properties file
- **THEN** each run writes `target/architecture-report.json`, and the enabled rule groups fail the
  build on violation
