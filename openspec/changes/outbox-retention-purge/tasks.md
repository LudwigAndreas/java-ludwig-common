## 1. Schema

- [ ] 1.1 Add `0006-finished-at.sql` (column, backfill, partial index, rollback) and include it in
      `outbox-changelog.xml`; verify `scripts/check_migrations.sh`
- [ ] 1.2 Map `finishedAt` on `OutboxMessage`; set it in `OutboxOutcomeRecorder` (published, dead-lettered) and
      `OutboxAdminService.discard`, clear it in `OutboxAdminService.retry`; verify `mvn -pl :outbox-spring-boot-starter
      test`

## 2. Purge job

- [ ] 2.1 `OutboxProperties.Retention` (`enabled`, `published=P7D`, `terminal=P30D`, `interval=PT1H`,
      `batch-size=500`, `drain-timeout=PT30S`) with bind-time validation; add `OutboxPropertiesValidationTest` for the
      `PT1H` and non-positive cases; verify `mvn -pl :outbox-spring-boot-starter test`
- [ ] 2.2 Repository purge statement (history and messages, one transaction, `SKIP LOCKED`, `LIMIT`); verify
      `mvn -pl :outbox-spring-boot-starter verify -Dit.test=OutboxRetentionPurgeIntegrationTest`
- [ ] 2.3 `OutboxRetentionPurge extends SelfSchedulingLifecycle` under `RunLock` `ludwig-outbox-retention-purge`,
      per-status passes, audit after commit (`PARTIAL` when rows remain), `OutboxMetrics.recordPurged(status, n)` and
      its Micrometer counter `ludwig.outbox.purged`; verify as 2.2
- [ ] 2.4 Register the bean in `OutboxAutoConfiguration` (property and `RunLock` conditions, WARN without a lock); add
      a fail-closed `OutboxRetentionAutoConfigurationTest` (disabled, no lock, defaults); verify
      `mvn -pl :outbox-spring-boot-starter test -Dtest=OutboxRetentionAutoConfigurationTest`

## 3. Scenarios

- [ ] 3.1 `OutboxRetentionPurgeIntegrationTest`: published-8-days purged, dead-letter-10-days kept, `FAILED` never
      purged, three-batch backlog, audit event per pass, retried-on-day-29 kept, appears in `GET /admin/v1/jobs`;
      verify `mvn -pl :outbox-spring-boot-starter verify`

## 4. Documentation

- [ ] 4.1 README.md and README.ru.md: configuration rows, "Data growth" rewritten, idempotency-window paragraph,
      runbook `DELETE` row replaced; verify the heading counts match, `diff <(grep -c '^#' README.md) <(grep -c '^#'
      README.ru.md)`
- [ ] 4.2 Remove the outbox item from `openspec/intents/admin-surface-follow-ups.md` §4; verify `openspec validate
      outbox-retention-purge --strict`

## 5. Verification gate

- [ ] 5.1 `scripts/gate.sh --change outbox-retention-purge outbox-spring-boot-starter`, which runs `mvn -q validate`,
      the module, and all nine in-repo dependents named in the proposal, then `openspec validate`
