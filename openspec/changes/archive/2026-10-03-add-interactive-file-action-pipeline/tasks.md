## 1. Module skeleton and POM topology

- [x] 1.1 Create `sources/file-action-spring-boot-starter/pom.xml`: artifactId
  `file-action-spring-boot-starter`, `<version>${revision}</version>`, parent `common` with
  `<relativePath>../../pom.xml</relativePath>`, importing `ludwig-bom` (`type=pom`, `scope=import`).
  Compile deps `web-core-spring-boot-starter`, `object-storage-spring-boot-starter`, `db-core`,
  `job-core`, `idempotency-spring-boot-starter`, `audit-core`; optional
  `security-spring-boot-starter`, `observability-spring-boot-starter`,
  `outbox-spring-boot-starter`; test `test-support`, `test-support-security`, `architecture-rules`.
  Verified by `mvn -pl :file-action-spring-boot-starter -am validate` and by
  `mvn -pl :file-action-spring-boot-starter help:effective-pom | grep -c 'ludwig-bom'` being non-zero.
- [x] 1.2 Add `<module>sources/file-action-spring-boot-starter</module>` to the root `pom.xml`.
  Verified by `mvn -q validate` listing the module in the reactor.
- [x] 1.3 Add `commons-csv` to `build/ludwig-bom/pom.xml` `dependencyManagement` with a
  `<commons-csv.version>` property and a comment recording that no XLSX dependency is added because
  `poi-ooxml` already ships `XSSFReader`. Verified by
  `mvn -pl :file-action-spring-boot-starter dependency:tree | grep commons-csv` showing the managed
  version and no second CSV or XLSX library.
- [x] 1.4 Regenerate the manifest with `scripts/manifest.sh build`, then confirm placement and
  staleness with `scripts/manifest.sh layout` and `scripts/manifest.sh stale` (both exit 0).

## 2. The declared surface the author writes against

- [x] 2.1 `ru.ludwigandreas.fileaction.api`: `@FileAction`, `RowBinding<R>` with its builder,
  `ColumnBinding`, `RowAddress` (sheet, 1-based row, column header name), `FileActionContext`.
  Verified by `mvn -pl :file-action-spring-boot-starter test -Dtest=RowBindingTest`.
- [x] 2.2 `sealed interface FileActionHandler<R>` permitting only `RowHandler<R>` and
  `DocumentHandler<R>`, with the javadoc on each stating the two unmechanisable rules (a `RowHandler`
  must be idempotent per row; a `DocumentHandler` must not call a partner inside the apply
  transaction). Verified by `mvn -pl :file-action-spring-boot-starter test -Dtest=HandlerSealingTest`,
  which asserts the permitted set.
- [x] 2.3 `FileActionState` (`UPLOADED VALIDATED APPLYING APPLIED REJECTED EXPIRED`) and its mapping
  onto `OperationStatus`. Verified by
  `mvn -pl :file-action-spring-boot-starter test -Dtest=FileActionStateMappingTest`.
- [x] 2.4 `FileScanner` SPI and `ScanOutcome`, with no shipped implementation. Verified by
  `mvn -pl :file-action-spring-boot-starter test -Dtest=FileScannerContractTest`.

## 3. Readers, under the memory and safety budget

- [x] 3.1 `ru.ludwigandreas.fileaction.format`: `FormatSniffer` (magic bytes, with the comment
  recording why Tika was rejected), `SourceFormat` closed to `XLSX` and `CSV`, and the BIFF refusal.
  Verified by `mvn -pl :file-action-spring-boot-starter test -Dtest=FormatSnifferTest`.
- [x] 3.2 `format.csv`: a streaming reader over `commons-csv` with BOM stripping, delimiter sniffing,
  header binding and ragged-row rejects. Verified by
  `mvn -pl :file-action-spring-boot-starter test -Dtest=CsvRowReaderTest`.
- [x] 3.3 `format.xlsx.read`: `XSSFReader` + `XSSFSheetXMLHandler` walk, `ZipSecureFile` ratio and
  entry-count ceilings, external-links refusal, shared-strings budget. Verified by
  `mvn -pl :file-action-spring-boot-starter test -Dtest=XlsxRowReaderTest` and
  `-Dtest=ZipBombRefusalTest`.
- [x] 3.4 `format.xlsx.write`: the only package referencing `SXSSFWorkbook` - the template generator
  and the annotated error report, with formula-injection neutralisation. A comment states what is and
  is not shared with `export`'s `ReportWriter` and why. Verified by
  `mvn -pl :file-action-spring-boot-starter test -Dtest=TemplateWriterTest,AnnotatedReportWriterTest`.
- [x] 3.5 Value coercion through `web-core`'s `UserPreferenceFormatter`, including the Excel serial-date
  path. Verified by `mvn -pl :file-action-spring-boot-starter test -Dtest=RowCoercionTest`, which
  covers a comma decimal separator and a `dd.MM.yyyy` locale.

## 4. Persistence and the lifecycle engine

- [x] 4.1 Liquibase changelog under `src/main/resources/db/changelog` for `file_action_submission` and
  `file_action_row_reject`, including the lease columns the deferred claim needs. Verified by
  `mvn -pl :file-action-spring-boot-starter verify -Dtest=none` running the Liquibase integration test
  `FileActionSchemaIT` against Testcontainers Postgres.
- [x] 4.2 Entities plus QueryDSL repositories - no JPQL, no SQL, no derived query methods, no new
  carve-out. Verified by
  `mvn -pl :file-action-spring-boot-starter test -Dtest=NoSqlStringsTest`, an ArchUnit test asserting
  the module contains no SQL string and no `@Query`.
- [x] 4.3 The engine: admission, `DigestInputStream` put to the object store, idempotency claim through
  `IdempotencyStore`, bind-and-validate, the three commit policies, the reject threshold, the bounded
  reject sample. Verified by
  `mvn -pl :file-action-spring-boot-starter test -Dtest=CommitPolicyTest,RejectThresholdTest`.
- [x] 4.4 `CONFIRM` mode: the `bound.ndjson` artifact, the confirm path that reads it rather than the
  upload, and TTL expiry. Verified by
  `mvn -pl :file-action-spring-boot-starter verify -Dit.test=ConfirmAppliesPreviewedRowsIT`, which
  mutates reference data between validate and confirm and asserts the applied rows are the previewed
  ones.
- [x] 4.5 Deferred execution on `job-core`: `SkipLockedClaim`, lease renewal, and the
  `file-action-retention` `RunLock` job. Verified by
  `mvn -pl :file-action-spring-boot-starter verify -Dit.test=DeferredClaimIT,RetentionIT`.
- [x] 4.6 Audit events through `audit-core`'s single `AuditSink` - submitted, rejected, scanned,
  applied - as typed records with `toAuditEvent()`, no try/catch around `record`. Verified by
  `mvn -pl :file-action-spring-boot-starter test -Dtest=FileActionAuditEventTest` and by the
  `RuleGroup.AUDIT` run in task 6.2.

## 5. HTTP surface, problem mappers, i18n and configuration validation

- [x] 5.1 The controller: submit, poll, confirm, cancel (202), rejects (`PageResponse`), error-report,
  template - all responses built with `OperationResponses`. Verified by
  `mvn -pl :file-action-spring-boot-starter test -Dtest=FileActionControllerTest`, asserting 200 for
  `INLINE`, 202 plus status header plus `Retry-After` for `DEFERRED`, and the terminal envelope for a
  cancel of an applied submission.
- [x] 5.2 Exception mappers contributed into `web-core`'s single RFC 9457 pipeline - no
  `@RestControllerAdvice` in this module. Verified by
  `mvn -pl :file-action-spring-boot-starter test -Dtest=ProblemMapperTest` and by the
  `NoLocalAdviceTest` ArchUnit test.
- [x] 5.3 `i18n/ludwig-file-action-messages.properties` and `_ru`. Verified by
  `mvn -pl :file-action-spring-boot-starter test -Dtest=MessageBundleParityTest`, which asserts
  identical key sets across both locales.
- [x] 5.4 `FileActionProperties` and `FileActionConfigurationValidator` covering every startup refusal
  the spec names: bean without configuration and configuration without bean, missing `commit-policy`,
  a row-level policy on a `DocumentHandler`, `scanning.mode: required` with no scanner, an `INLINE`
  action above the inline ceiling, a binding key missing from either locale, `required-authority` with
  security absent, and `IdempotencyEndpointMatcher` matching a submit path. Verified by
  `mvn -pl :file-action-spring-boot-starter test -Dtest=FileActionConfigurationValidatorTest`, one
  case per refusal.
- [x] 5.5 `FileActionAutoConfiguration` registered in
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, with
  `@ConditionalOnClass` guards on the optional dependencies. Verified by
  `mvn -pl :file-action-spring-boot-starter test -Dtest=FileActionAutoConfigurationTest` using
  `ApplicationContextRunner`.

## 6. The checks that make the conventions real

- [x] 6.1 `PoiConfinementTest` and `NoMaterialisationTest` (module-local ArchUnit), each with a javadoc
  paragraph saying why the rule is module-local rather than a platform `RuleGroup` - `export`
  legitimately DOM-reads an administrator-supplied template. Verified by
  `mvn -pl :file-action-spring-boot-starter test -Dtest=PoiConfinementTest,NoMaterialisationTest`, and
  each test confirmed to fail when the forbidden call is temporarily introduced.
- [x] 6.2 Enable the platform rule groups for this module with an `ArchitectureRulesTest` subclass
  annotated `@AnalyzeArchitecture` plus `src/test/resources/architecture-rules.properties`, covering
  `OPERATIONS`, `AUDIT`, `CACHING` and `PRESENTATION`. Verified by
  `mvn -pl :file-action-spring-boot-starter test -Dtest=FileActionArchitectureTest` and by
  `target/architecture-report.json` listing those groups as enabled.
- [x] 6.3 Add `RuleGroup.UPLOADS` to `architecture-rules` - `MultipartFile` may not appear as a
  parameter, field or return type outside `file-action-spring-boot-starter` - with javadoc stating what
  it prevents. Verified by `mvn -pl :architecture-rules test -Dtest=UploadsRuleGroupTest`.
- [x] 6.4 `LargeWorkbookHeapIT`: a 25 MiB, 100,000-row workbook read under a measured heap ceiling, on
  the precedent of `file-ingest`'s 200 MB test. Verified by
  `mvn -pl :file-action-spring-boot-starter verify -Dit.test=LargeWorkbookHeapIT`, and confirmed to
  fail when the reader is switched to a DOM read.

## 7. Documentation

- [x] 7.1 `sources/file-action-spring-boot-starter/README.md` and `README.ru.md`: what the author
  writes, the full configuration tree, why the bytes are always stored, why `INLINE` and `DEFERRED`
  share one envelope, the sealed handler split, the commit-policy table, the POI confinement, why there
  is no `file-format-core` and the condition that would create one, and the module's declared size
  ceiling with the pointer to `file-ingest-spring-boot-starter` above it. Verified by
  `python3 -c` comparing the `##` heading sets of the two files for equality.
- [x] 7.2 Add the unmechanisable rules to `docs/harness-enforcement.md` as explicitly unenforced, with
  their reasons. Verified by `openspec validate add-interactive-file-action-pipeline` and by the file
  naming all four items listed in `design.md`.

## 8. Reference implementation in crud-service-example

- [x] 8.1 Add the `file-action-spring-boot-starter` dependency and an `order-import` action - a
  `RowBinding<OrderLineRow>`, a `RowHandler` with `commit-policy: PER_ROW`, and a Liquibase changeset
  for whatever the handler writes. Verified by
  `mvn -pl :crud-service-example test -Dtest=OrderImportActionTest`.
- [x] 8.2 Integration tests: a clean file applies, a file with two bad rows applies 398 and serves two
  rejects, an oversized file is refused toward the ingest path, a double submit returns the first
  envelope, `CONFIRM` mode requires the confirm call, and the template endpoint produces a file that
  binds. Verified by `mvn -pl :crud-service-example verify -Dit.test='OrderImport*IT'`.
- [x] 8.3 Update `services/crud-service-example/README.md` and `README.ru.md` with the action, both
  locales. Verified by the heading-set comparison as in 7.1.

## 9. The verification gate, in full

A `ludwig-bom` change and a root-POM module addition have no narrower gate than a full reactor build,
per `CLAUDE.md`. `scripts/gate.sh --list :file-action-spring-boot-starter` prints the exact commands;
run them rather than retyping them.

- [x] 9.1 `scripts/manifest.sh build` (a POM changed), then `scripts/manifest.sh stale` and
  `scripts/manifest.sh layout` both exit 0.
- [x] 9.1b `scripts/check_image_pins.sh` and `scripts/check_api_baseline.sh`, which
  `scripts/gate.sh --list` prints and which this group originally omitted. Added here to match the
  change's `state.json` contract, whose `done_when` is authoritative.
- [x] 9.2 `mvn -q validate` - Checkstyle, every module.
- [x] 9.3 `mvn clean install` - the full reactor, required by the `ludwig-bom` and root-POM changes.
- [x] 9.4 `mvn -pl :file-action-spring-boot-starter -am verify` and
  `mvn -pl :crud-service-example -am verify` - the touched modules, with failsafe.
- [x] 9.5 Every in-repo dependent of `architecture-rules`, which this change modifies:
  `mvn -pl :crud-service-example,:file-ingest-spring-boot-starter,:messaging-spring-boot-starter,:notification-service,:object-storage-spring-boot-starter,:user-settings-spring-boot-starter -am verify`.
- [x] 9.6 `python3 scripts/check_aggregate_report.py` - the new jar module appears in the aggregate
  coverage report.
- [x] 9.7 `openspec validate add-interactive-file-action-pipeline`.
- [x] 9.8 Write `openspec/changes/add-interactive-file-action-pipeline/receipt.json` per
  `docs/agent-state.md`, with the real gate output, test counts, retries and anything unresolved.
