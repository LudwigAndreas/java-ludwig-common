## 1. Bookkeeping

- [x] 1.1 Write `state.json` with its `contract` block before the first code edit.
  Proves it: `python3 -m json.tool openspec/changes/remove-compatibility-shims/state.json`

## 2. Kafka envelope headers (design D1)

- [x] 2.1 `messaging-spring-boot-starter`:
  - remove `MessageHeaders.LEGACY_*` and its "legacy names" javadoc;
  - make `EnvelopeReader` and `VersionGatingDeserializer` read only the canonical names;
  - add tests that a legacy-only record reads as an absent header;
  - update the README in both locales and the `SettingsEventListener` javadoc in `user-settings`.

  Proves it: `mvn -pl :messaging-spring-boot-starter -am verify`
- [x] 2.2 `outbox-spring-boot-starter`: make `KafkaOutboxDispatcher` write the canonical names only, with
  a test asserting that none of the four pre-prefix names is on the record.
  Proves it: `mvn -pl :outbox-spring-boot-starter -am verify`

## 3. odata-filter advice (design D2)

- [x] 3.1 Delete `ODataFilterExceptionHandler` and its bean and property. Make `web-core-spring-boot-starter`
  required and make the mapper and the bundle unconditional. Rewrite `ODataFilterWebAutoConfigurationTest`.
  Add the no-advice architecture test and re-enable the two rules the old advice justified disabling.
  Update the README of odata-filter, web-core and crud-service-example, each in both locales.
  Proves it: `mvn -pl :odata-filter-spring-boot-starter -am verify`

## 4. Observability constructors (design D3)

- [x] 4.1 Remove the non-canonical constructors of `LogFieldNames` and `JsonLogEncoderConfig`, and update
  any test that used them.
  Proves it: `mvn -pl :observability-spring-boot-starter -am verify`

## 5. Redaction markers (design D4)

- [x] 5.1 Remove `Redaction.LEGACY_SETTINGS_MASK` and `LEGACY_HEADER_MASK`, and narrow `isMask`.
  `AuditStoreIntegrationTest` spells `[redacted]` itself. Add a unit test that `isMask` rejects both retired
  markers. Update the READMEs that name the constants.
  Proves it: `mvn -pl :audit-core,:audit-spring-boot-starter -am verify`

## 6. Release record

- [x] 6.1 Add the twelve `revapi.differences` entries of design D5 to the root POM.
  Proves it: `mvn -q validate`
- [x] 6.2 Correct the stale grpc-client POM comment. List every removal, its replacement and the header
  upgrade precondition under the 3.0.0 section of `CHANGELOG.md` and `CHANGELOG.ru.md`.
  Proves it: `scripts/check_build_metadata.sh`

## 7. Verification gate, in full

- [x] 7.1 Manifest current, layout clean, file-only checks.
  Proves it: `scripts/manifest.sh build && scripts/manifest.sh stale && scripts/manifest.sh layout && scripts/check_migrations.sh && scripts/check_image_pins.sh`
- [x] 7.2 Full build, because the root POM changes, and the aggregate report.
  Proves it: `mvn -q validate && mvn clean install && python3 scripts/check_aggregate_report.py`
- [x] 7.3 Validate the change.
  Proves it: `openspec validate remove-compatibility-shims --strict`
