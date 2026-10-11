## Context

Coverage is already produced: every library writes `jacoco.exec`, services write a merged exec, and
`build/jacoco-aggregate` turns all of it into one XML report at `verify`. The pipeline is Jenkins.
The repository is `LudwigAndreas/java-ludwig-common` on GitHub.

## Decisions

**D1. Configuration lives in the root POM, not in `sonar-project.properties`.** The Maven scanner
does not read that file; it reads POM and user properties. A properties file would be a second
place that looks authoritative and is ignored.

**D2. Root POM only; `ludwig-service-parent` is untouched.** The scanner is an aggregator: project
identity is read from the execution root, and SonarCloud's JaCoCo XML importer is a project-level
sensor, so it also reads the report path from the root. The two services do not inherit the root
POM and do not need to. Putting a repository-relative report path into `ludwig-service-parent`
would publish it to every downstream service, where the path does not exist.

**D3. One coverage input: the aggregate report.** `sonar.coverage.jacoco.xmlReportPaths` is
`${maven.multiModuleProjectDirectory}/build/jacoco-aggregate/target/site/jacoco-aggregate/jacoco.xml`.
An absolute path anchored at the reactor root resolves identically from every module. The
aggregate is the only report that carries cross-module coverage (a starter exercised by a
service's integration tests) and the services' merged unit + integration data.

**D4. The plugin is pinned in `pluginManagement` and bound to nothing.** Analysis uploads source to
an external service; it must be an explicit command, the same reasoning as `-Pci`. The pipeline
invokes it by full coordinates (`org.sonarsource.scanner.maven:sonar-maven-plugin:sonar`) so the
version comes from `pluginManagement` rather than from prefix resolution.

**D5. Identity values are the SonarCloud defaults for a GitHub-bound project** -
organization `ludwigandreas`, project key `LudwigAndreas_java-ludwig-common` - and are properties,
so `-Dsonar.projectKey=...` overrides them without an edit. They were inferred from the git remote,
not read from SonarCloud; see `pending_human` in `state.json`.

**D6. The token is `SONAR_TOKEN` in the environment, from a Jenkins secret-text credential.** The
scanner reads that variable natively. No `sonar.token` property anywhere. This is not mechanically
enforced, consistently with `docs/harness-enforcement.md` item 9 (credentials are Sonar's and the
SCM's to catch); a comment at the properties states it.

**D7. The stage runs after `Build and test`, in the same workspace, as a bare goal.** The classes,
the execution data and the aggregate report are already in `target/`, and `install` has put every
module in the local repository, so the scanner resolves dependencies without rebuilding.

**D8. The quality gate does not fail the build yet.** `sonar.qualitygate.wait` is not set. A gate
introduced on the day of the first analysis fails on the backlog rather than on the change; turning
it on is its own change, argued from what the first analyses say - the reasoning
`build/jacoco-aggregate/README.md` already gives for having no platform threshold.

## Enforcement

The one new rule a build can check - the Sonar coverage path equals the aggregate report's location
- is asserted by `scripts/check_aggregate_report.py`, which the gate already runs after a full
build and which already owns that path.

## Risks

- First analysis may reject the project key or organization (D5). Fix is one `-D` or one property.
- SonarCloud "Automatic Analysis" must be switched off in the project's settings, or CI-based
  analysis is refused. That is a setting in SonarCloud, not in this repository.
