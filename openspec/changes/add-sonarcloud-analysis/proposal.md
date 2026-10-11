## Why

The enforcement triad names SonarQube as the owner of bugs and security, and nothing in the
repository runs it: there is no scanner plugin, no project identity and no pipeline stage. A
SonarCloud project now exists for this repository, so the third tool can stop being prose.

## What Changes

- The root POM pins `sonar-maven-plugin` in `pluginManagement` and declares the analysis identity
  as properties: `sonar.host.url`, `sonar.organization`, `sonar.projectKey`.
- The root POM points `sonar.coverage.jacoco.xmlReportPaths` at the one aggregate JaCoCo report
  that `build/jacoco-aggregate` already writes. No new coverage mechanism, no per-module report
  paths, no change to any JaCoCo execution or threshold.
- The `Jenkinsfile` gains a `Sonar analysis` stage after `Build and test`. The token is a Jenkins
  secret-text credential bound to `SONAR_TOKEN` for that stage only; it appears in no POM.
- `scripts/check_aggregate_report.py` fails when the Sonar coverage path and the aggregate report
  location disagree, so moving one without the other cannot silently report 0% coverage.
- `README.md` and `build/jacoco-aggregate/README.md` / `README.ru.md` document
  the analysis command and what the report feeds.

Nothing is **BREAKING**. The default build is unchanged: the scanner is bound to no phase and
`mvn clean install` never contacts SonarCloud. No published artifact changes.

## Capabilities

### New Capabilities

- `static-analysis`: how the platform is analysed by SonarCloud - where the project identity
  lives, which coverage report the analysis reads, how it is invoked and where its token comes from.

### Modified Capabilities

None. `enforcement-triad` already assigns bugs and security to SonarQube; `pom-topology` already
places plugin versions and build configuration for libraries in the root POM.

## Impact

- `pom.xml` (root): four properties, one `pluginManagement` entry.
- `Jenkinsfile`: one stage, one credential id (`sonarcloud-token`) that must exist in Jenkins.
- `scripts/check_aggregate_report.py`: one assertion.
- READMEs in both locales. `CHANGELOG.md` / `CHANGELOG.ru.md` at archive.
- `ludwig-bom` and `ludwig-service-parent` are not touched.
