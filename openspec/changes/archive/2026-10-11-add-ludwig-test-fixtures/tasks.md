## 1. Durable state

- [x] 1.1 Write `openspec/changes/add-ludwig-test-fixtures/state.json` with its `contract` block
  before the first edit, per `docs/agent-state.md`: goal, output, the constraints from design D2,
  D5 and D11, and `done_when` as the commands in group 10. Proves:
  `python3 -c "import json;print(json.load(open('openspec/changes/add-ludwig-test-fixtures/state.json'))['contract']['done_when'])"`

## 2. Module skeleton (`test-support-local`, root POM, BOM, aggregate)

- [x] 2.1 Create `sources/test-support-local/pom.xml`: parent `common` with
  `<relativePath>../../pom.xml</relativePath>`, imports `ludwig-bom`, `jar` packaging, compile
  dependency on `test-support`, optional dependency on `rest-client-spring-boot-starter`, optional
  `spring-kafka`, `org.testcontainers:kafka`, `org.testcontainers:localstack`, WireMock (the shaded
  artifact the BOM manages) and ArchUnit, no `<version>` anywhere, and the POM comment from design
  D2 naming the modules that can never depend on this one. Proves:
  `mvn -q -pl :test-support-local -am validate` (run after 2.2 and 2.3)
- [x] 2.2 In the root `pom.xml`, add `<module>sources/test-support-local</module>` after
  `sources/test-support-security`. Proves: `mvn -q -pl :test-support-local -am validate`
- [x] 2.3 In `build/ludwig-bom/pom.xml`, add the managed entry for `test-support-local` at
  `${project.version}` directly after `test-support-security`. Proves:
  `mvn -q -pl :ludwig-bom install -DskipTests && mvn -q -pl :test-support-local -am validate`
- [x] 2.4 In `build/jacoco-aggregate/pom.xml`, add the dependency on `test-support-local` beside
  `test-support-security`. Proves (after group 10's full build):
  `python3 scripts/check_aggregate_report.py`
- [x] 2.5 Regenerate the manifest and confirm role and placement. Proves:
  `scripts/manifest.sh build && scripts/manifest.sh stale && scripts/manifest.sh layout && scripts/manifest.sh module sources/test-support-local | grep -E "role +test-support"`

## 3. Partner stubs (`test-support-local`)

- [x] 3.1 Add the sample application under
  `src/test/java/ru/ludwigandreas/testsupport/local/integration`: a `@SpringBootApplication`, one
  `@LudwigRestClient("partner")` interface, and an `application.yml` whose
  `ludwig.rest-client.clients.partner.base-url` is an unroutable remote URL. Proves:
  `mvn -q -pl :test-support-local -am test-compile`
- [x] 3.2 Add `PartnerStub`, `StubCatalog` and the pre-refresh initializer in
  `ru.ludwigandreas.testsupport.local.stub`: one loopback-bound server per stub, dynamic port
  unless `ludwig.local.stubs.<name>.port` is set, `base-url` published at highest precedence, extra
  properties contributed by a stub, duplicate client name refused. Add
  `StubWiringIT`: the sample client reaches the stub despite the remote URL in
  `application.yml`; the recorded request carries the client's headers; a taken configured port and
  a duplicate name each fail start-up with the client named; a non-loopback address is refused.
  **If precedence cannot be made to hold, stop and record it in `failures` (design, Risks).**
  Proves:
  `mvn -pl :test-support-local -am verify -Dit.test=StubWiringIT -Dfailsafe.failIfNoSpecifiedTests=false`
- [x] 3.3 Add the fault model (design D6): the five faults, the `fault` scenario with one
  high-priority mapping per fault, per-path scoping from the Java API, `refused` as listener
  stop/start on the same port, the `slow-delay` and `fault` start-up properties with an unknown
  name rejected listing the valid ones. Add `StubFaultIT` covering each scenario of
  "A stub can be made to fail" and "A fault can be switched from three places", including setting
  the state through the admin URL with a plain HTTP call. Proves:
  `mvn -pl :test-support-local -am verify -Dit.test=StubFaultIT -Dfailsafe.failIfNoSpecifiedTests=false`
- [x] 3.4 Add the JUnit extension that starts a `StubCatalog` before the context and, after each
  test method, restores healthy mappings, the fault start state and an empty request journal
  without deleting the fault mappings. Add `StubResetIT`: a fault and a recorded
  request set in one method are absent in the next. Proves:
  `mvn -pl :test-support-local -am verify -Dit.test=StubResetIT -Dfailsafe.failIfNoSpecifiedTests=false`

## 4. Launcher (`test-support-local`)

- [x] 4.1 Add `LocalRun` in `ru.ludwigandreas.testsupport.local` (design D4): `from(main)`,
  `postgres()`, `kafka()`, `localstack()`, `redis()`, `stubs(...)`, `fixtures(...)`,
  `profiles(...)`, `run(args)`, delegating to `test-support`'s configuration and initializers, with
  a named-but-absent client library failing before any container starts, and no profile activated
  by default. Add a `LocalSampleApplication` in the sample's `local` package. Add
  `LocalRunIT`: the sample starts through `LocalRun` with PostgreSQL only and no other
  container is running; with Kafka named, `spring.kafka.bootstrap-servers` resolves to the broker;
  an unnamed `local` profile's properties are not applied. Proves:
  `mvn -pl :test-support-local -am verify -Dit.test=LocalRunIT -Dfailsafe.failIfNoSpecifiedTests=false`
- [x] 4.2 Add the start-up check (design D7): `RestClientRegistry.names()` against the catalog and
  its pass-through declarations in both directions, plus
  `ludwig.local.stubs.<name>.mode=passthrough` for one launch; guarded so a service without the
  rest-client starter starts. Add `StubCoverageIT` covering the four launcher-side
  scenarios of "Every rest client has a stub or an explicit pass-through". Proves:
  `mvn -pl :test-support-local -am verify -Dit.test=StubCoverageIT -Dfailsafe.failIfNoSpecifiedTests=false`
- [x] 4.3 Add the start-up summary, logged through SLF4J once after fixtures have run: backing
  services with addresses, each stub with client name, base URL, admin URL, active fault and the
  ready-to-paste scenario-state call, pass-through clients, fixture mode and fixtures run. Add
  `StartupSummaryIT` asserting the captured log contains the JDBC URL and both URLs of
  a stub. Proves:
  `mvn -pl :test-support-local -am verify -Dit.test=StartupSummaryIT -Dfailsafe.failIfNoSpecifiedTests=false`
- [x] 4.4 Add `InertOnClasspathIT`: a plain `@SpringBootTest` of the sample, without
  `LocalRun` or the extension, starts no stub and overrides no property, and the module's jar
  contains no `AutoConfiguration.imports`. Proves:
  `mvn -pl :test-support-local -am verify -Dit.test=InertOnClasspathIT -Dfailsafe.failIfNoSpecifiedTests=false`

## 5. Seed fixtures (`test-support-local`)

- [x] 5.1 Give the sample one table through a changelog that satisfies the migration rules, one
  entity and repository, and one topic with a listener. Proves:
  `scripts/check_migrations.sh && mvn -q -pl :test-support-local -am test-compile`
- [x] 5.2 Add `SeedFixture` and the runner in `ru.ludwigandreas.testsupport.local.fixture` (design
  D8): declared predecessors, cycle detection naming both fixtures, one transaction per fixture,
  stop at the first failure, modes `reset` / `keep` / `none` under `ludwig.local.fixtures.mode`
  with `reset` the default and `DatabaseCleaner` doing the emptying, and the comment on
  `SeedFixture` recording why "a fixture is code, not a SQL script" has no check. Add
  `SeedFixtureIT` covering every scenario of the first three `seed-fixture`
  requirements and the on-demand call from a test. Proves:
  `mvn -pl :test-support-local -am verify -Dit.test=SeedFixtureIT -Dfailsafe.failIfNoSpecifiedTests=false`
- [x] 5.3 Add the Kafka helpers over `KafkaOperations` and `ProducerRecord`: publish seed records,
  deliver one record N times, and a fixture's declaration that it needs Kafka with start-up failing
  when Kafka was not named. Add `DuplicateDeliveryIT`: the sample listener receives
  two records with identical key, headers and payload at different offsets. Proves:
  `mvn -pl :test-support-local -am verify -Dit.test=DuplicateDeliveryIT -Dfailsafe.failIfNoSpecifiedTests=false`

## 6. The build-time rule (`test-support-local`)

- [x] 6.1 Add the abstract `LocalRunRulesTest` in `ru.ludwigandreas.testsupport.local.rules`
  (design D7): imports main and test classes, fails for a `@LudwigRestClient` interface whose name
  has no stub and no pass-through in the named catalog, and for a class calling `LocalRun` outside
  a package named `local`. Javadoc states why it is not a `RuleGroup` in `architecture-rules`.
  Proves: `mvn -q -pl :test-support-local -am compile`
- [x] 6.2 Under `src/test/java/ru/ludwigandreas/testsupport/local/architecture`, add the sample's
  subclass (passes) and `LocalRunRulesSelfTest`, which evaluates the rule against fixture classes
  for each violation and asserts the failure names the interface and the client. Proves:
  `mvn -pl :test-support-local -am test -Dtest='LocalRunRules*' -Dsurefire.failIfNoSpecifiedTests=false`

## 7. Test-scope enforcement (`ludwig-service-parent`)

- [x] 7.1 In `build/ludwig-service-parent/pom.xml`, add a `bannedDependencies` rule to the existing
  `enforce-platform-rules` execution excluding `ru.ludwigandreas:test-support*` at compile and
  runtime scope, with a comment saying a local stand-in must not reach an image. Proves:
  `mvn -q -pl :ludwig-service-parent install -DskipTests && mvn -q -pl :crud-service-example,:notification-service -am validate`
- [x] 7.2 Demonstrate the rule fails, without committing the edit: temporarily remove
  `<scope>test</scope>` from `test-support` in `services/crud-service-example/pom.xml`, confirm
  `mvn -q -pl :crud-service-example -am validate` exits non-zero naming the artifact, restore the
  file, and record the observed message in `state.json` `decisions`. Proves:
  `git diff --exit-code services/crud-service-example/pom.xml`

## 8. Module documentation (`test-support-local`)

- [x] 8.1 Write `sources/test-support-local/README.md` and `README.ru.md` with the same headings:
  that a stub is a belief about a partner and what pass-through is for; a complete
  `Local<Name>Application`; writing a stub and a catalog; the fault table with the admin call for
  each; fixtures and the three modes; the token-endpoint and authenticated-fixture cases; what the
  module deliberately does not do; the API marked provisional until a service consumes it. No image
  reference without a digest. Proves:
  `diff <(grep '^#' sources/test-support-local/README.md | sed 's/[^#].*//') <(grep '^#' sources/test-support-local/README.ru.md | sed 's/[^#].*//') && scripts/check_image_pins.sh && mvn -q validate`

## 9. Repository documentation

- [x] 9.1 In `sources/test-support/README.md` and `README.ru.md`, add one pointer beside
  `WireMockFixture` to `test-support-local` for a stub wired by client name. No code or POM change
  in `test-support`. Proves:
  `grep -c "test-support-local" sources/test-support/README.md sources/test-support/README.ru.md && git diff --exit-code sources/test-support/pom.xml sources/test-support/src`
- [x] 9.2 Update the module counts and the layout table in `CLAUDE.md` and the matching lines in
  `openspec/config.yaml` (`sources/` 23 -> 24, reactor 29 -> 30), and add the two new checks to the
  "Enforced" section of `docs/harness-enforcement.md` with the "fixture is code" convention under
  the backlog. Proves:
  `grep -n "24" CLAUDE.md openspec/config.yaml | grep -i sources && grep -c "test-support-local" docs/harness-enforcement.md`
- [x] 9.3 Add an entry under the unreleased heading of `CHANGELOG.md` and the same heading of
  `CHANGELOG.ru.md` naming `test-support-local`. Proves:
  `diff <(grep '^## ' CHANGELOG.md) <(grep '^## ' CHANGELOG.ru.md) && grep -c "test-support-local" CHANGELOG.md CHANGELOG.ru.md`

## 10. Verification gate

- [x] 10.1 Regenerate and check the manifest. Proves:
  `scripts/manifest.sh build && scripts/manifest.sh stale && scripts/manifest.sh layout`
- [x] 10.2 Checkstyle on every module. Proves: `mvn -q validate`
- [ ] 10.3 The whole reactor, because `ludwig-bom` and `ludwig-service-parent` changed and there
  is no narrower gate. Proves: `scripts/gate.sh --change add-ludwig-test-fixtures --full`
  (which is `mvn clean install` plus the gate scripts)
- [x] 10.4 The aggregate report contains the new module. Proves:
  `python3 scripts/check_aggregate_report.py`
- [x] 10.5 Migration and image-pin scripts. Proves:
  `scripts/check_migrations.sh && scripts/check_image_pins.sh`
- [x] 10.6 The change validates. Proves: `openspec validate add-ludwig-test-fixtures --strict`
- [x] 10.7 Write `openspec/changes/add-ludwig-test-fixtures/receipt.json` per
  `docs/agent-state.md`. Proves:
  `python3 -c "import json;json.load(open('openspec/changes/add-ludwig-test-fixtures/receipt.json'))"`
