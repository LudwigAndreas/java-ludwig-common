## ADDED Requirements

### Requirement: The analysis identity lives in the root POM

The root POM SHALL declare `sonar.host.url`, `sonar.organization` and `sonar.projectKey` as
properties and SHALL pin `sonar-maven-plugin` in `pluginManagement`. The repository SHALL NOT
contain a `sonar-project.properties` file, because the Maven scanner does not read one.
`ludwig-bom` and `ludwig-service-parent` SHALL NOT carry Sonar configuration.

#### Scenario: The analysis is run against a different project

- **WHEN** the scanner is invoked with `-Dsonar.projectKey=<other>`
- **THEN** the analysis is reported to that project, with no file edited

#### Scenario: A service outside this repository inherits the service parent

- **WHEN** a downstream service is parented by `ludwig-service-parent`
- **THEN** it inherits no Sonar project key, organization or report path

### Requirement: Coverage comes from the aggregate report

The analysis SHALL read coverage from the single JaCoCo XML report written by
`build/jacoco-aggregate`, named by `sonar.coverage.jacoco.xmlReportPaths` as an absolute path
anchored at the reactor root. The property and the report's actual location SHALL agree, and the
build's verification gate SHALL fail when they do not.

#### Scenario: The aggregate report is moved

- **WHEN** the aggregate report's output directory changes and the Sonar property does not
- **THEN** `scripts/check_aggregate_report.py` exits non-zero and names both paths

#### Scenario: A starter is covered only by a service's integration tests

- **WHEN** code in a library module is executed by an integration test in a service
- **THEN** the analysis counts it as covered, because the aggregate report does

### Requirement: Analysis is an explicit command

The scanner SHALL NOT be bound to a lifecycle phase. `mvn clean install` SHALL NOT contact
SonarCloud. The pipeline SHALL run the analysis in its own stage after the full build, invoking the
plugin by its full coordinates so that the version is the one pinned in `pluginManagement`.

#### Scenario: A developer builds locally

- **WHEN** `mvn clean install` is run with no network access to SonarCloud
- **THEN** the build result is unaffected

#### Scenario: The pipeline analyses a build

- **WHEN** the `Build and test` stage has passed
- **THEN** the `Sonar analysis` stage runs
  `org.sonarsource.scanner.maven:sonar-maven-plugin:sonar` in the same workspace

### Requirement: The token never appears in the repository

The SonarCloud token SHALL be supplied as the `SONAR_TOKEN` environment variable from a pipeline
credential, bound only for the analysis stage. No POM, properties file or script SHALL contain a
`sonar.token` or `sonar.login` value.

#### Scenario: The analysis stage runs

- **WHEN** the pipeline reaches `Sonar analysis`
- **THEN** `SONAR_TOKEN` is bound from the Jenkins credential `sonarcloud-token` for that stage and
  is absent from every other stage
