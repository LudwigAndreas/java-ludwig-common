## 1. Shared pieces

- [x] 1.1 `db-core`: `AdminQueryFilter` with the OData-backed implementation under `@ConditionalOnClass` and the
      unavailable fallback (`400`, `ludwig.admin.filter-unavailable`, bundle EN+RU); optional dependency on
      `odata-filter-spring-boot-starter`; unit tests for both; verify `mvn -pl :db-core -am verify`
- [x] 1.2 `security-spring-boot-starter`: `AdminSurfaceGuard` refuses `$orderby`, `$top`, `$skip`, `$count` under
      `/admin/**` with `400`; scenario in `AdminSurfaceMvcTest`; verify `mvn -pl :security-spring-boot-starter test
      -Dtest=AdminSurfaceMvcTest`

## 2. Adopters (one module per task)

- [x] 2.1 `audit-spring-boot-starter`: `@Filterable` on the spec's fields of `AuditEventEntity`, `$filter` on
      `GET /admin/v1/audit/events` ANDed under confinement, `filter` summary on the search's audit event;
      `AuditTrailSearchIntegrationTest` covers a filtered search, cross-tenant non-widening, an excluded field
      (`reason`) refused, and values absent from the audit; verify `mvn -pl :audit-spring-boot-starter verify`
- [x] 2.2 `outbox-spring-boot-starter`: `@Filterable` on `OutboxMessage`'s spec fields, `$filter` on the messages
      listing; `OutboxAdminIntegrationTest` covers the dead-letter scenario and `lastError` refused; verify
      `mvn -pl :outbox-spring-boot-starter verify`
- [x] 2.3 `reconciliation-spring-boot-starter`: `@Filterable` on `SyncRun`'s spec fields, `$filter` on a task's runs
      within the path's task; `ReconciliationRunHistoryIntegrationTest` covers failed manual runs and `resultJson`
      refused; verify `mvn -pl :reconciliation-spring-boot-starter verify`
- [x] 2.4 `export-spring-boot-starter`: `@Filterable` on `ExportReportRun`'s spec fields, `$filter` on the runs
      listing; the export admin integration test covers one requester's failed runs and `filterExpression` refused;
      verify `mvn -pl :export-spring-boot-starter verify`
- [x] 2.5 `file-action-spring-boot-starter`: `@Filterable` on `FileActionSubmissionEntity`'s spec fields, `$filter`
      on the submissions listing; the file-action admin integration test covers rejected-row imports and
      `declaredFilename` refused; verify `mvn -pl :file-action-spring-boot-starter verify`
- [x] 2.6 `pat-spring-boot-starter`: `@Filterable` on `PatEntity`'s spec fields, `$filter` on the token listing; the
      PAT admin integration test covers unused tokens and `secretDigest` refused; verify
      `mvn -pl :pat-spring-boot-starter verify`

## 3. Services and API documents

- [x] 3.1 Regenerate both services' OpenAPI documents (`$filter` on the six listings) with `scripts/api-docs.sh
      refresh`; confirm no administrative entity appears in the OData metadata discovery; verify
      `scripts/api-docs.sh check` and `mvn -pl :notification-service,:crud-service-example -am verify`

## 4. API baseline and documentation

- [x] 4.1 revapi accepted-difference entries in the root `pom.xml` for the repository and service methods that gain a
      parameter; `scripts/manifest.sh build`; verify `mvn -q validate` and `scripts/manifest.sh stale`
- [x] 4.2 READMEs (EN and RU) of the six adopters (filterable fields, indexed ones first) and of `db-core`, plus an
      admin guide paragraph; add the change to the follow-ups list's record; verify heading-count parity per README
      pair, i18n bundle key parity, and `openspec validate admin-listing-odata-filter --strict`

## 5. Verification gate

- [x] 5.1 `scripts/gate.sh --change admin-listing-odata-filter db-core security-spring-boot-starter
      audit-spring-boot-starter outbox-spring-boot-starter reconciliation-spring-boot-starter
      export-spring-boot-starter file-action-spring-boot-starter pat-spring-boot-starter`
