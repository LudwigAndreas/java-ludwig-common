## 1. Schema and recording

- [x] 1.1 `0015-create-run.sql` and its changelog include; `SyncRun` entity and repository; verify
      `scripts/check_migrations.sh` and `mvn -pl :reconciliation-spring-boot-starter test`
- [x] 1.2 `RunRecorder` (insert on lease, `RUNNING`, terminal mapping from `RunOutcome`, counts, failure code,
      swallow-and-meter on write failure) wired into `TaskRunner.run`, `dryRun` and `backfill`; unit test for the
      outcome-to-status mapping; verify `mvn -pl :reconciliation-spring-boot-starter test`

## 2. Asynchronous manual runs

- [x] 2.1 `TaskRunner.start` with lease-on-request-thread, the `reconciliationManualRunExecutor` bean
      (`runs.manual-concurrency`), and `429` on rejection; verify `mvn -pl :reconciliation-spring-boot-starter verify
      -Dit.test=ReconciliationRunHistoryIntegrationTest`
- [x] 2.2 Controller: `runs` and `backfills` return the row's envelope with the `/runs/{runId}` location; remove
      `finished(...)`; update `ReconciliationAdminIntegrationTest` for the asynchronous contract; verify `mvn -pl
      :reconciliation-spring-boot-starter verify -Dit.test=ReconciliationAdminIntegrationTest`

## 3. Read endpoints

- [x] 3.1 `GET .../runs` (keyset, `tier`, `status`) and `GET .../runs/{runId}` under `reconciliation.read`, with the
      abandoned-run `UPDATE` in `RunPurge` (task 4.1); bundle keys for the new error codes in
      `i18n/ludwig-reconciliation-messages{,_ru}.properties`; verify the scenarios in
      `ReconciliationRunHistoryIntegrationTest`, and key-set parity with `cd
      sources/reconciliation-spring-boot-starter/src/main/resources/i18n && diff <(cut -d= -f1
      ludwig-reconciliation-messages.properties | sort) <(cut -d= -f1 ludwig-reconciliation-messages_ru.properties |
      sort)`

## 4. Retention

- [x] 4.1 `ludwig.reconciliation.runs.retention` with validation, plus `RunPurge` under lock
      `ludwig-reconciliation-run-purge`; cover the 31-day scenario; verify as 2.1

## 5. Cross-thread lease

- [x] 5.1 Add a cross-thread release case to `job-core`'s `JdbcRunLockIT`; verify `mvn -pl :job-core verify
      -Dit.test=JdbcRunLockIT`

## 6. Documentation

- [x] 6.1 README.md and README.ru.md: the Administration section regenerated, plus the configuration rows and
      runbook ("a run is stuck RUNNING"); remove the run-history item from `openspec/intents/admin-surface-follow-ups.md`;
      verify the heading counts match and `openspec validate reconciliation-run-history --strict`

## 7. Verification gate

- [x] 7.1 `scripts/gate.sh --change reconciliation-run-history reconciliation-spring-boot-starter job-core` (job-core
      for task 5.1's test, with its in-repo dependents)
