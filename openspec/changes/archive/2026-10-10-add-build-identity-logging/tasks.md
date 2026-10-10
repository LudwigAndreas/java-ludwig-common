## 1. Bake provenance into the service artifact (`ludwig-service-parent`)

- [x] 1.1 In `build/ludwig-service-parent/pom.xml`, configure `git-commit-id-maven-plugin` to generate
  the properties file: `generateGitPropertiesFile=true`, the filename under `target/classes`, and the
  included properties limited to commit id, abbreviated commit id, branch, commit time and the dirty
  flag. Keep `failOnNoGitDirectory=false` and `failOnUnableToExtractRepoInfo=false` so a build with no
  checkout still succeeds. Proves: `mvn -pl :crud-service-example -am package -DskipTests && test -f services/crud-service-example/target/classes/git.properties && cat services/crud-service-example/target/classes/git.properties`
- [x] 1.2 In the same POM, bind `spring-boot-maven-plugin`'s `build-info` goal, with `build.time`
  overridden to day precision per design D8 and a comment stating the digest-stability limit that
  truncation buys and does not buy. Proves: `mvn -pl :crud-service-example -am package -DskipTests && cat services/crud-service-example/target/classes/META-INF/build-info.properties`
- [x] 1.3 In the same POM, accept the CI build number as a property defaulting to empty and emit it as
  a `build-info` additional property, so it is absent rather than fabricated on a local build. Proves:
  `mvn -pl :crud-service-example -am package -DskipTests -Dludwig.ci.build-number=4711 && grep 4711 services/crud-service-example/target/classes/META-INF/build-info.properties`
- [x] 1.4 Correct the two drifted comments: the `git-commit-id-maven-plugin` block's claim about
  `git.properties` (now true — reword so it states what the configuration above actually does), and
  `ServiceIdentityResolver.java:65-67`'s dead `build.version` reference. Deferred to task 3.2 for the
  resolver file itself; here, only the POM comment. Proves: `mvn -q validate`
- [x] 1.5 Verify a build with no git checkout still succeeds and simply omits the resource. Proves:
  `rm -rf /tmp/nogit && git archive HEAD | (mkdir -p /tmp/nogit && tar -x -C /tmp/nogit) && (cd /tmp/nogit && mvn -q -pl :crud-service-example -am package -DskipTests) && test ! -f /tmp/nogit/services/crud-service-example/target/classes/git.properties`

## 2. Lock step 1 against regression (`scripts/`)

- [x] 2.1 Add `scripts/check_build_metadata.py` asserting that `build/ludwig-service-parent/pom.xml`
  configures `git-commit-id-maven-plugin` to generate its properties file and binds
  `spring-boot-maven-plugin`'s `build-info` goal, naming whichever is missing. Model it on
  `scripts/check_migrations.py`. Proves: `python3 scripts/check_build_metadata.py`
- [x] 2.2 Add `scripts/check_build_metadata.sh` as the thin wrapper, matching
  `scripts/check_migrations.sh`, and add a negative fixture under `scripts/testdata/` so the check is
  shown to fail when the configuration is absent. Proves: `scripts/check_build_metadata.sh`
- [x] 2.3 Register the script in `gate.sh`'s `COMMANDS` array alongside `check_migrations.sh`, with the
  one-line comment explaining why it is a script rather than an ArchUnit or Checkstyle rule. Proves:
  `scripts/gate.sh --list :observability-spring-boot-starter | grep check_build_metadata`

## 3. Resolve and publish build identity (`observability-spring-boot-starter`)

- [x] 3.1 Add `ru.ludwigandreas.observability.core.BuildIdentity` — a record of `commitId`,
  `abbreviatedCommitId`, `branch`, `buildTimestamp`, `ciBuildNumber`, `dirty`, blank-normalised in the
  compact constructor as `ServiceIdentity` does, with javadoc stating why it is separate from
  `ServiceIdentity` (design D1) and that its fields must never become metric tags. Proves:
  `mvn -pl :observability-spring-boot-starter -am test`
- [x] 3.2 Add `BuildIdentityResolver` reading `git.properties` and `META-INF/build-info.properties` from
  the classpath, each field independently, preferring an explicit `ludwig.observability.build.*`
  property, and degrading a parse failure to all-fields-absent. Remove the dead `build.version` fallback
  comment in `ServiceIdentityResolver` and point version resolution at the now-real build-info resource.
  Proves: `mvn -pl :observability-spring-boot-starter test -Dtest=BuildIdentityResolverTest`
- [x] 3.3 Add a unit test for the resolver covering: all fields present, no `git.properties` at all, a
  malformed resource, an absent CI build number, and an explicit property overriding a resource value.
  Proves: `mvn -pl :observability-spring-boot-starter test -Dtest=BuildIdentityResolverTest`
- [x] 3.4 Extend `ObservabilityEnvironmentPostProcessor` to resolve the build identity and publish it
  into the existing `ludwig-observability-defaults` source, using the same `putIfPresent` shape so an
  unresolved field is omitted rather than written empty. Proves:
  `mvn -pl :observability-spring-boot-starter test -Dtest=ObservabilityEnvironmentPostProcessorTest`
- [ ] 3.5 Add the `ludwig.observability.build.*` properties to `ObservabilityProperties` with javadoc,
  and assert `BuildIdentity` reports no breaking revapi difference against the baseline while
  `ServiceIdentity` is untouched. Proves:
  `mvn -pl :observability-spring-boot-starter -am verify && scripts/check_api_baseline.sh`

## 4. Profile-dependent console format (`observability-spring-boot-starter`)

- [x] 4.1 In `ObservabilityEnvironmentPostProcessor`, default
  `ludwig.observability.logging.json.enabled` to `!activeProfiles.contains("local")`, published with the
  existing `addLast` so an explicit setting still wins. Hard-code the profile name as a constant with a
  comment stating why it is deliberately not configurable (design D3). Proves:
  `mvn -pl :observability-spring-boot-starter test -Dtest=ObservabilityEnvironmentPostProcessorTest`
- [x] 4.2 Update `ObservabilityProperties.Logging.Json#enabled`'s javadoc: it no longer says "on by
  default" unconditionally, and records the profile-dependent default and the reason for both halves.
  Proves: `mvn -q validate`
- [x] 4.3 Add post-processor tests for all four cases: no profile, `local` active, `local` active with an
  explicit `true`, and a non-`local` profile. Proves:
  `mvn -pl :observability-spring-boot-starter test -Dtest=ObservabilityEnvironmentPostProcessorTest`

## 5. Build identity in the log stream (`observability-spring-boot-starter`)

- [x] 5.1 Add a `commitId` component to `LogFieldNames` and name it in all three factories — `ecs()`,
  `otel()`, `flat()` — following each set's own convention (design D4; the `ecs` name is the design's
  one open question, default `service.commit.id`). Proves:
  `mvn -pl :observability-spring-boot-starter test -Dtest=LogFieldNamesTest`
- [x] 5.2 Write the abbreviated commit id onto every event in `JsonLogEncoder`, from the resolved
  identity rather than the MDC, omitting it when absent. Proves:
  `mvn -pl :observability-spring-boot-starter test -Dtest=JsonLogEncoderTest`
- [x] 5.3 In `JsonLoggingInitializer`, when the format resolves to text, install a Logback pattern
  carrying the service name, version and abbreviated commit id as literals and the correlation, trace
  and span ids as `%mdc{...}` conversions. Leave the existing deference intact: only
  `LayoutWrappingEncoder` instances are touched, so a service-authored appender is still left alone.
  Proves: `mvn -pl :observability-spring-boot-starter test -Dtest=JsonLoggingInitializerTest`
- [x] 5.4 Add the cross-format test the design makes load-bearing: assert that every field the
  `service-log-stream` spec calls mandatory appears in the JSON output for each of the three field sets
  **and** in the text pattern output. Proves:
  `mvn -pl :observability-spring-boot-starter test -Dtest=MandatoryLogFieldsTest`
- [x] 5.5 Emit the startup identity event — one event at startup carrying service name, version,
  environment, instance, commit id, branch, build timestamp, CI build number and dirty flag, omitting
  unresolved fields. Proves:
  `mvn -pl :observability-spring-boot-starter test -Dtest=StartupIdentityEventTest`
- [x] 5.6 Add an integration test asserting the format is in effect for the first line written, in both
  formats, so the startup event and any bootstrap failure are never in the wrong shape. Must be named
  `…IntegrationTest` or failsafe never runs it. Proves:
  `mvn -pl :observability-spring-boot-starter verify -Dit.test=LogStreamFormatIntegrationTest`
- [x] 5.7 Update `README.md` and `README.ru.md`: the profile-dependent default replaces the manual
  `local` override advice, the new field appears in the field-set table and the sample log line, the
  startup event and the build-identity properties are documented, and the configuration table gains the
  new rows. Key sets must match across both locales. Proves: `mvn -q validate && mvn -pl :observability-spring-boot-starter -am verify`

## 6. Enforcement (`architecture-rules`)

- [x] 6.1 Add `RuleGroup.LOGGING("logging", true)` to
  `build/architecture-rules/src/main/java/ru/ludwigandreas/archrules/RuleGroup.java`, with javadoc
  stating what separates a second encoder implementation from a service's own logging configuration —
  the distinction is the rule. Proves: `mvn -pl :architecture-rules -am test`
- [x] 6.2 Add the rules in that group: no type outside `ru.ludwigandreas.observability` implements
  Logback's `Encoder` or `Layout`; no second type holds build provenance (shaped like the
  `RuleGroup.OPERATIONS` restatement rule); no production source invokes a git process or reads a `.git`
  path. Reference the external types by string name, as the existing groups do, so no compile dependency
  is added in either direction. Proves: `mvn -pl :architecture-rules -am test`
- [x] 6.3 Add rule tests with a positive and a negative fixture for each of the three rules, so each is
  shown to fail on the shape it governs. Proves: `mvn -pl :architecture-rules -am verify`
- [x] 6.4 Update `build/architecture-rules/README.md` and `README.ru.md` with the new group and its three
  rules, matching key sets across locales. Proves: `mvn -q validate`

## 7. Prove the provenance reaches a packaged service (`crud-service-example`)

- [x] 7.1 Add an integration test asserting both provenance resources are on the classpath and the git
  resource carries a non-empty commit id — the check that would have caught the original drift. Must be
  named `…IntegrationTest`. Proves:
  `mvn -pl :crud-service-example verify -Dit.test=BuildProvenanceIntegrationTest`
- [x] 7.2 Enable `RuleGroup.LOGGING` in the service's `@AnalyzeArchitecture` annotation and
  `src/test/resources/architecture-rules.properties`. Proves:
  `mvn -pl :crud-service-example verify && test -f services/crud-service-example/target/architecture-report.json`

## 8. Verification gate, in full

- [x] 8.1 Regenerate the module manifest, because a POM changed. Proves:
  `scripts/manifest.sh build && scripts/manifest.sh stale && scripts/manifest.sh layout`
- [x] 8.2 Validate the change artifacts. Proves: `openspec validate add-build-identity-logging`
- [ ] 8.3 Run the **full** gate. `ludwig-service-parent` is edited, so there is no narrower gate than
  the whole reactor — a parent has no dependents in `project-index.json` because inheritance is not a
  dependency edge, and a narrow `-pl` build would verify almost nothing. Proves:
  `scripts/gate.sh --change add-build-identity-logging --full`
- [x] 8.4 Confirm the aggregate coverage report still includes every jar module, since a new module's
  worth of classes was added to two existing ones. Proves:
  `python3 scripts/check_aggregate_report.py`
