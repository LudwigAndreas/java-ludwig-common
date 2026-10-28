## Why

The verification gate is not trustworthy on `master`:

- `scripts/gate.sh` stops at its first command on every change. `check_image_pins.sh` reads the prose
  "no new image: the mail server" in an archived proposal as an unpinned image called `the`.
- Three integration tests fail intermittently, so a red run no longer means anything. One of them points to a
  production defect rather than a test defect:
  - `PatDirectAuthenticationIT$FilterEnabled.revokedTokenStopsWorking`, first recorded 2026-10-22;
  - `StubFaultIT.slow`;
  - `JdbcRunLockIT`.

  `PatUsageTracker`'s asynchronous last-used write is a full read-modify-save of the PAT row. When it overlaps a
  revoke, one of the two loses an optimistic-lock race. If the revoke loses, an operator's revoke answers `500`
  while the token keeps working.

All four were recorded in `openspec/intents/admin-surface-follow-ups.md` §1 and §4.

## What Changes

- `check_image_pins.py`: in Markdown files, only fenced code blocks and inline code spans are examined. Prose is no
  longer a candidate, while a tag-only reference in a README code example still fails. A `--self-test` with
  fixtures under `scripts/testdata/check_image_pins/` makes every trigger both fire and not fire, as the
  migrations, protos and build-metadata checkers already do.
- `PatUsageTracker`: the last-used write becomes a single conditional `UPDATE` touching only `last_used_at`,
  `last_used_ip` and `use_count`, without the entity version. A revoke or rotation that read the row before
  the write landed no longer fails its own save on a bumped version.
- `StubFaultIT.slow`: investigate and fix the cause of the intermittent "1 request where 2 were expected".
- `JdbcRunLockIT`: investigate the intermittent "error occurred while setting up the SSL connection" against the
  Testcontainers Postgres and remove it.

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `container-image-pinning`: adds a requirement saying what the repository-wide checker treats as a reference in
  Markdown.
- `personal-access-token`: adds a requirement that a token's use never refuses or undoes a concurrent revocation.

## Non-goals

- Running `check_api_baseline.sh` against the releases repository. That needs network access to
  `nexus.example.internal`, not a code change, and it stays on the follow-ups list as a release-checklist item.
- Binding failsafe in `security-spring-boot-starter` (that is `admin-surface-polish`).
- Changing what is debounced or audited on a PAT use.
- Retrying flaky tests (surefire `rerunFailingTestsCount`). That hides exactly the defect found here.

## Impact

- `scripts/check_image_pins.py` and `scripts/check_image_pins.sh`, plus new `scripts/testdata/check_image_pins/`.
  These are repository tooling, not a module.
- `pat-spring-boot-starter` (library; parent `common`). No in-repo dependents.
- `test-support-local` (library; parent `common`). In-repo dependent: `notification-service` (service).
- `job-core` (library; parent `common`), test sources only. In-repo dependents:
  - `audit-spring-boot-starter`
  - `export-spring-boot-starter`
  - `file-action-spring-boot-starter`
  - `file-ingest-spring-boot-starter`
  - `idempotency-spring-boot-starter`
  - `messaging-db-spring-boot-starter`
  - `notification-service`
  - `outbox-spring-boot-starter`
  - `reconciliation-spring-boot-starter`
- Shared contracts: `container-image-pinning` and `personal-access-token` are modified.
- No POM changes.
