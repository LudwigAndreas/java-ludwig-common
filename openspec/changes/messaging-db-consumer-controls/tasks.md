## 1. Schema and claim

- [ ] 1.1 `0005-create-consumer-pause.sql` (advisory lock, `IF NOT EXISTS`) plus its include; verify
      `scripts/check_migrations.sh`
- [ ] 1.2 Paused pairs excluded in `LogStore.claim`; verify `mvn -pl :messaging-db-spring-boot-starter verify
      -Dit.test=ConsumerPauseIT` (three containers in one group: paused topic undelivered, others delivered)

## 2. Operations

- [ ] 2.1 `pause`, `resume`, `moveOffsets` in the admin service (pause required, `FOR UPDATE`, target resolution,
      counts, audit) and the three endpoints; groups are resolved from the local listener registry; verify `mvn -pl
      :messaging-db-spring-boot-starter verify -Dit.test=ConsumerOffsetMoveIT` covering the poison-record,
      replay-by-timestamp and not-paused (`409`) scenarios
- [ ] 2.2 Listing shows pause state; update `MessagingAdminIT` (replace the `405` scenario); verify `mvn -pl
      :messaging-db-spring-boot-starter verify`

## 3. Documentation

- [ ] 3.1 README.md and README.ru.md: the Administration section plus runbook rows for "poison record" and "replay after
      a fix" (including the dedup caveat); regenerate the OpenAPI documents of services that run the starter; remove
      the offset item from the follow-ups list; verify heading-count parity and `openspec validate
      messaging-db-consumer-controls --strict`

## 4. Verification gate

- [ ] 4.1 `scripts/gate.sh --change messaging-db-consumer-controls messaging-db-spring-boot-starter`
