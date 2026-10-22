## 1. Durable state

- [x] 1.1 Write `openspec/changes/add-server-info-endpoint/state.json` with its `contract` block
  before the first edit, per `docs/agent-state.md`: goal, output, constraints, and `done_when` as
  the commands printed by `scripts/gate.sh --list` for `observability-spring-boot-starter`,
  `notification-service` and `crud-service-example`. Proves:
  `python3 -c "import json;print(json.load(open('openspec/changes/add-server-info-endpoint/state.json'))['contract']['done_when'])"`

## 2. The endpoint (`observability-spring-boot-starter`)

- [x] 2.1 In `sources/observability-spring-boot-starter/pom.xml`, declare `jackson-annotations` as an
  optional dependency with no version, in the optional servlet block, with a comment saying it is
  used by the server info response only. Then regenerate the manifest. Proves:
  `scripts/manifest.sh build && scripts/manifest.sh stale && mvn -q -pl :observability-spring-boot-starter -am compile`
- [x] 2.2 Add `ServerInfoResponse` in `ru.ludwigandreas.observability.web`: a record with `service`,
  `version`, `environment`, `commit`, `built`, annotated to omit null members, and a static factory
  taking `ServiceIdentity` and `BuildIdentity` that maps each member by name (design D2), renders
  `built` as the UTC date of the build timestamp and leaves it absent when the text is not an
  instant (D3). Javadoc states that the member set is specified by the `server-info` capability and
  pinned by the test in 2.3. Proves: `mvn -q -pl :observability-spring-boot-starter -am compile`
- [x] 2.3 Add `ServerInfoResponseTest` under `src/test/java/ru/ludwigandreas/observability/unit`:
  fully populated identities serialize to exactly the five members and contain none of the branch,
  dirty flag, CI build number, full commit id, namespace or instance values; `2026-09-16T00:00:00Z`
  becomes `2026-09-16`; unparseable timestamp text omits `built`; absent members are omitted, not
  `null`; all-absent identities serialize to `{}`. Proves:
  `mvn -pl :observability-spring-boot-starter -am test -Dtest=ServerInfoResponseTest -Dsurefire.failIfNoSpecifiedTests=false`
- [x] 2.4 Add `ServerInfoController` in the same package with the path `/server/info` as a public
  constant and one `GET` handler returning the response built from the two identity beans. No
  exception handler, no advice, no security configuration. Proves:
  `mvn -q -pl :observability-spring-boot-starter -am compile`
- [x] 2.5 Add the `ludwig.observability.server.info.enabled` property (default `true`) to
  `ObservabilityProperties` with javadoc, and register the controller as a bean in
  `ObservabilityWebAutoConfiguration` under `@ConditionalOnMissingBean` and that property (D5).
  Proves: `mvn -q -pl :observability-spring-boot-starter -am compile && grep -c "ludwig.observability.server.info.enabled" sources/observability-spring-boot-starter/target/classes/META-INF/spring-configuration-metadata.json`
- [x] 2.6 Add `ServerInfoEndpointIntegrationTest` under
  `src/test/java/ru/ludwigandreas/observability/integration`, beside
  `ObservabilityAutoConfigurationTest`: a servlet context answers `GET /server/info` with 200,
  `application/json` and the configured service, version and environment; with
  `ludwig.observability.server.info.enabled=false` the path answers 404; with
  `ludwig.observability.enabled=false` likewise; a non-web context starts and has no controller
  bean. Proves:
  `mvn -pl :observability-spring-boot-starter -am verify -Dit.test=ServerInfoEndpointIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=false`
- [x] 2.7 Document the endpoint in `sources/observability-spring-boot-starter/README.md` and
  `README.ru.md`, next to the existing "Build identity" section: the path, a sample body, the fields
  it withholds and why, the switch, that anonymous access is the service's own
  `ludwig.security.public-paths` entry, and that it should be read once per page load. Proves:
  `grep -c "/server/info" sources/observability-spring-boot-starter/README.md sources/observability-spring-boot-starter/README.ru.md && mvn -q validate`

## 3. Serving it from `notification-service`

- [x] 3.1 Add `/server/info` to `ludwig.security.public-paths` in
  `services/notification-service/src/main/resources/application.yml`, with a one-line comment that
  the document is a curated allow-list and therefore safe without credentials. Proves:
  `grep -n "/server/info" services/notification-service/src/main/resources/application.yml`
- [x] 3.2 Add `ServerInfoIT` under
  `services/notification-service/src/test/java/ru/ludwigandreas/notification/integration`: a request
  with no credentials gets 200 and a body whose `service` is the service's name and which has no
  member outside the five. Proves:
  `mvn -pl :notification-service -am verify -Dit.test=ServerInfoIT -Dfailsafe.failIfNoSpecifiedTests=false`
- [x] 3.3 Refresh the committed API document and read the diff: it must add the `/server/info` path
  and the response schema and change nothing else. Proves:
  `mvn -pl :notification-service verify -Dit.test=ApiDocumentIT -DfailIfNoTests=false -Dludwig.apidocs.write=true && git diff --stat docs/api/notification-service.openapi.yaml`

## 4. Let rest-client and observability start together (`rest-client-spring-boot-starter`)

- [x] 4.1 Add `CallContextSourceWiringTest` under
  `src/test/java/ru/ludwigandreas/restclient/integration`, booting the starter's auto-configurations
  (including `RestClientObservabilityAutoConfiguration`) with observability's
  `ObservabilityCoreAutoConfiguration`: the context starts with exactly one `CallContextSource`, the
  platform one; without observability's auto-configuration the single source is `NONE`; with
  `ludwig.observability.enabled=false` likewise; an application-declared source is the only one.
  Run it before the fix and record that the first case fails with the two-bean error. Proves (fails
  first): `mvn -pl :rest-client-spring-boot-starter -am test -Dtest=CallContextSourceWiringTest -Dsurefire.failIfNoSpecifiedTests=false`
- [x] 4.2 In `RestClientObservabilityAutoConfiguration`, order the configuration before
  `RestClientAutoConfiguration` and after observability's core auto-configuration by name, add
  `@ConditionalOnMissingBean(CallContextSource.class)` to the platform source, and update the class
  javadoc and the fallback's javadoc in `RestClientAutoConfiguration` to state the ordering and why
  it is load-bearing (design D9). Proves:
  `mvn -pl :rest-client-spring-boot-starter -am test -Dtest=CallContextSourceWiringTest -Dsurefire.failIfNoSpecifiedTests=false`
- [x] 4.3 Run the module's whole build, so the reordering is shown not to disturb the existing
  client, auth, slice and resilience tests. Proves: `mvn -pl :rest-client-spring-boot-starter -am verify`

## 5. Serving it from `crud-service-example`

- [x] 5.1 In `services/crud-service-example/pom.xml`, add `observability-spring-boot-starter` as a
  compile dependency with no version and a comment naming what it brings, as
  `notification-service`'s POM does. Regenerate the manifest; the service must now appear among the
  starter's dependents. Proves:
  `scripts/manifest.sh build && scripts/manifest.sh stale && scripts/manifest.sh module sources/observability-spring-boot-starter | grep crud-service-example`
- [x] 5.2 Before any other edit to the service, run its full build with the starter on the
  classpath, so that a failure in an existing test is attributable to the dependency alone (design
  D8). A failure is fixed in the service's test configuration, never by changing the starter;
  record it in `state.json` `failures` either way. The one expected failure is `ApiDocumentIT`, on
  the new `/server/info` path, until 5.5. Proves: `mvn -pl :crud-service-example -am verify`
- [x] 5.3 Add a `ludwig.security.public-paths` block to
  `services/crud-service-example/src/main/resources/application.yml` listing `/actuator/health`,
  `/actuator/health/**`, `/actuator/info` and `/server/info`, with a comment that a list here
  replaces the security starter's default rather than extending it, which is why the three actuator
  entries are restated. Proves:
  `grep -n -A6 "public-paths" services/crud-service-example/src/main/resources/application.yml`
- [x] 5.4 Add `ServerInfoIT` under
  `services/crud-service-example/src/test/java/ru/ludwigandreas/example/catalog/integration`: with no
  credentials, `GET /server/info` gets 200 with `service` equal to `product-catalog` and no member
  outside the five, and `GET /actuator/health` still gets 200. Proves:
  `mvn -pl :crud-service-example -am verify -Dit.test=ServerInfoIT -Dfailsafe.failIfNoSpecifiedTests=false`
- [x] 5.5 Refresh the committed API document and read the diff. It may add the `/server/info` path
  and its response schema; anything else the starter caused to appear is a finding to record in
  `state.json` `decisions` before committing, not to wave through. Proves:
  `mvn -pl :crud-service-example -am verify -Dit.test=ApiDocumentIT -Dfailsafe.failIfNoSpecifiedTests=false -Dludwig.apidocs.write=true && git diff --stat docs/api/crud-service-example.openapi.yaml`
  (`-am` is required: without it the starter is taken from `~/.m2` and the document is regenerated
  against the previously installed jar)
- [x] 5.6 Update `services/crud-service-example/README.md` and `README.ru.md`: the service includes
  the observability starter, what that changes in its console output outside the `local` profile,
  and that it serves `/server/info` publicly. Proves:
  `grep -c "/server/info" services/crud-service-example/README.md services/crud-service-example/README.ru.md && mvn -q validate`

## 6. Verification gate

- [ ] 6.1 Run the gate in full for the four touched modules; it works out the dependents of
  `rest-client-spring-boot-starter` (`crud-service-example`, `export-spring-boot-starter`,
  `reconciliation-spring-boot-starter`) and of `observability-spring-boot-starter` (`crud-service-example`, `export-spring-boot-starter`,
  `file-action-spring-boot-starter`, `notification-service`, `reconciliation-spring-boot-starter`,
  `rest-client-spring-boot-starter`, `user-settings-spring-boot-starter`) and validates the change.
  Proves:
  `scripts/gate.sh --change add-server-info-endpoint observability-spring-boot-starter rest-client-spring-boot-starter notification-service crud-service-example`
- [ ] 6.2 Confirm the Java API baseline reports no breaking difference for the starter, since the
  change is additive. Proves: `scripts/check_api_baseline.sh`
- [ ] 6.3 Write `openspec/changes/add-server-info-endpoint/receipt.json` per `docs/agent-state.md`
  with the real gate results, test counts, retries and anything unresolved. Proves:
  `python3 -c "import json;json.load(open('openspec/changes/add-server-info-endpoint/receipt.json'))" && openspec validate add-server-info-endpoint`
