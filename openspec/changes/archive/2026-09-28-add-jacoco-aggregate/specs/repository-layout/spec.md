## ADDED Requirements

### Requirement: Coverage instrumentation is inherited, never opted into
Every module parented by the reactor root SHALL be instrumented by JaCoCo through the root POM's
active plugin section. A module SHALL NOT need to declare `jacoco-maven-plugin` to be measured.

#### Scenario: A new library or starter is added
- **WHEN** a module is added under `sources/` with no JaCoCo declaration of its own
- **THEN** it is instrumented, produces `target/jacoco.exec`, and appears in the aggregate report.
  Opting in per module is what left `object-storage-spring-boot-starter`,
  `file-ingest-spring-boot-starter`, `test-support`, `test-support-security` and `checkstyle-rules`
  with no coverage at all while twenty siblings had it

#### Scenario: A module needs different coverage configuration
- **WHEN** a module genuinely needs its own thresholds or excludes
- **THEN** it may re-declare the plugin with that configuration, which merges over the inherited
  executions. A bare re-declaration adding no configuration is redundant and is removed

### Requirement: One aggregate report covers the whole platform
`build/jacoco-aggregate` SHALL depend on every jar module in the repository and bind
`jacoco:report-aggregate` to `verify`, producing a single report over all of them.

#### Scenario: The reactor is built
- **WHEN** `mvn clean install` or `mvn verify` runs over the full reactor
- **THEN** `build/jacoco-aggregate/target/site/jacoco-aggregate/` holds one report whose covered
  and missed counts span all 27 jar modules

#### Scenario: A module is added and not wired into the aggregator
- **WHEN** a new jar module is added to the reactor but not declared as a dependency of
  `jacoco-aggregate`
- **THEN** the aggregate silently omits it. **No check currently catches this** — the report is
  still generated and still green. The manifest records every module, so a comparison between the
  aggregator's dependencies and the manifest's jar modules is the check this rule is missing; until
  it exists, adding a module means adding it here too

#### Scenario: The aggregator is built before the modules it measures
- **WHEN** the reactor is ordered
- **THEN** the aggregator is built last, because it depends on everything. Its report would
  otherwise read exec files that the current build had not yet written

### Requirement: The aggregator does not widen any module's verification gate
The manifest SHALL exclude `jacoco-aggregate` from every module's `inRepoDependents`.

#### Scenario: The gate for a single module is computed
- **WHEN** `scripts/manifest.sh module <path>` or `scripts/gate.sh --list <module>` runs
- **THEN** `jacoco-aggregate` is not among the dependents. Including it would make every narrow
  gate a full-reactor build, which is the thing a narrow gate exists to avoid — and the aggregator
  has no code that an API change could break

### Requirement: The aggregator publishes nothing
`jacoco-aggregate` SHALL NOT be deployed, and SHALL NOT appear in `ludwig-bom`.

#### Scenario: A release is deployed
- **WHEN** `mvn -Prelease -Pci deploy` runs
- **THEN** no `jacoco-aggregate` artifact is published. It is a build-time report, and a consumer
  has nothing to resolve from it
