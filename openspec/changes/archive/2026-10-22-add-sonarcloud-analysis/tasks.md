## 1. Durable state

- [x] 1.1 Write `state.json` with its `contract` block before the first edit. Proves:
  `python3 -c "import json;print(json.load(open('openspec/changes/add-sonarcloud-analysis/state.json'))['contract']['done_when'])"`

## 2. Root POM

- [x] 2.1 Add `sonar-maven-plugin.version`, `sonar.host.url`, `sonar.organization`,
  `sonar.projectKey` and `sonar.coverage.jacoco.xmlReportPaths` to the root POM's properties, with
  a comment stating D2, D3 and D6. Proves:
  `mvn -q -N help:evaluate -Dexpression=sonar.coverage.jacoco.xmlReportPaths -DforceStdout`
- [x] 2.2 Pin `org.sonarsource.scanner.maven:sonar-maven-plugin` in the root `pluginManagement`,
  bound to no phase. Proves:
  `mvn -q -N help:effective-pom | grep -A2 sonar-maven-plugin` and `scripts/manifest.sh stale`

## 3. Enforcement

- [x] 3.1 `scripts/check_aggregate_report.py` reads `sonar.coverage.jacoco.xmlReportPaths` from the
  root POM and fails (exit 1) when it is absent or does not end with the report path the script
  already checks. Proves: `python3 scripts/check_aggregate_report.py` after a full build, and the
  same command exiting 1 against a temporarily altered copy of the path.

## 4. Pipeline

- [x] 4.1 Add the `Sonar analysis` stage to the `Jenkinsfile` after `Build and test`, with
  `SONAR_TOKEN` bound from the secret-text credential `sonarcloud-token`. Proves: review; the
  stage cannot be run from a workstation without the token.

## 5. Documentation

- [x] 5.1 `README.md` (there is no root `README.ru.md`): the analysis command, where its configuration lives,
  where the token comes from. `build/jacoco-aggregate/README.md` and `README.ru.md`: the report is
  what SonarCloud reads, and the check that ties the two. Proves: `openspec validate add-sonarcloud-analysis`

## 6. Gate

- [ ] 6.1 The root-POM gate: `scripts/check_image_pins.sh`, `scripts/check_api_baseline.sh`,
  `scripts/check_migrations.sh`, `scripts/check_build_metadata.sh`, `mvn -q validate`,
  `mvn clean install`, `python3 scripts/check_aggregate_report.py`,
  `openspec validate add-sonarcloud-analysis`.
- [ ] 6.2 Write `receipt.json` per `docs/agent-state.md`.
