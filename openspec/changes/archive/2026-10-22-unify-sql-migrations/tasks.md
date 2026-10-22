## 1. The checker, before anything it checks

- [x] 1.1 Write `scripts/check_migrations.py` implementing every check listed in design D7, reading
      a NUL-separated file list on stdin and taking an optional `--list` argument, mirroring
      `scripts/check_image_pins.py`'s interface. Verify: `python3 -c "import ast,sys;
      ast.parse(open('scripts/check_migrations.py').read())"` exits 0.
- [x] 1.2 Write `scripts/check_migrations.sh` as the wrapper: assert `python3` and `git` are on
      PATH, support `--list`, enumerate changelog files with `git ls-files -z` so that
      `target/classes/db/changelog/**` is never seen, and pipe them to the Python checker. Exit
      codes 0/1/3 as `check_image_pins.sh` uses them. Verify: `chmod +x scripts/check_migrations.sh
      && scripts/check_migrations.sh --list` prints all 26 root/changeset files found on the
      unconverted tree and exits 0.
- [x] 1.3 Confirm the checker *fails* the unconverted tree for the right reason, so that it is known
      to be capable of failing at all. Verify: `scripts/check_migrations.sh; echo "exit=$?"` exits 1
      and names XML-authored changesets, not some other error.
- [x] 1.4 Add a self-test fixture pair under `scripts/testdata/check_migrations/` — one conforming
      module tree and one violating every rule once — and a `--self-test` mode that runs the
      checker against both. Verify: `scripts/check_migrations.sh --self-test` exits 0 and reports
      each rule as having been both passed and failed once.

## 2. `job-core` — the reference conversion

- [x] 2.1 Convert `sources/job-core/src/main/resources/db/changelog/job-core/`: reduce
      `job-core-changelog.xml` to includes-only with the pinned `dbchangelog-4.27.xsd`, and create
      `0001-create-run-lock.sql` holding `--changeset ludwig-job-core:job-core-0001-create-run-lock
      dbms:postgresql`, the per-column rationale comments kept verbatim as `--` prose, and
      `--rollback DROP TABLE job_run_lock`. Verify: `scripts/check_migrations.sh` reports no
      violation in `job-core`, and `mvn -pl :job-core -am verify` passes (its `JdbcRunLockIT` builds
      the schema from this changelog against Postgres).

## 3. The starters, in reactor dependency order

- [x] 3.1 `outbox-spring-boot-starter`: 5 changesets → `0001..0005`, slugs unchanged (design D4).
      Verify: `mvn -pl :outbox-spring-boot-starter -am verify` and `scripts/check_migrations.sh`.
- [x] 3.2 `idempotency-spring-boot-starter`: 2 changesets → `0001..0002`, preserving the
      `--preconditions onFail:MARK_RAN` / `--precondition-table-exists
      table:notification_idempotency` guard on `0002` and its row-moving `INSERT ... SELECT`.
      Verify: `mvn -pl :idempotency-spring-boot-starter -am verify` (its `NotificationMigrationIT`
      is the test that exercises the guard) and `scripts/check_migrations.sh`.
- [x] 3.3 `identity-projection-spring-boot-starter`: 7 changesets → `0001..0007`, slugs unchanged.
      Verify: `mvn -pl :identity-projection-spring-boot-starter -am verify` and
      `scripts/check_migrations.sh`.
- [x] 3.4 `audit-spring-boot-starter`: 4 changesets → `0001..0004`, keeping both
      `--precondition-table-exists` guards and the set-based row-moving statements. Leave the
      deliberately-empty `src/test/resources/db/changelog/db.changelog-master.xml` as an
      includes-only root with its explanatory comment verbatim, pinning the XSD. Verify:
      `mvn -pl :audit-spring-boot-starter -am verify` and `scripts/check_migrations.sh`.
- [x] 3.5 `export-spring-boot-starter`: 14 changesets → `0001..0014`, slugs unchanged. Verify:
      `mvn -pl :export-spring-boot-starter -am verify` and `scripts/check_migrations.sh`.
- [x] 3.6 `file-action-spring-boot-starter`: 2 changesets → `0001..0002`. Verify:
      `mvn -pl :file-action-spring-boot-starter -am verify` and `scripts/check_migrations.sh`.
- [x] 3.7 `file-ingest-spring-boot-starter`: 4 changesets → `0001..0004`, **prefix renamed**
      `ingest-` → `file-ingest-` per design D3, keeping the `--precondition-sql-check` guard on
      `0004-rename-completed-to-succeeded`. Verify: `mvn -pl :file-ingest-spring-boot-starter -am
      verify` and `scripts/check_migrations.sh`.
- [x] 3.8 `pat-spring-boot-starter`: 3 changesets → `0001..0003`, folding the existing
      `<sql dbms="postgresql">` into the changeset-level `dbms:postgresql`. Verify:
      `mvn -pl :pat-spring-boot-starter -am verify` (its `PatSchemaIT` asserts the schema) and
      `scripts/check_migrations.sh`.
- [x] 3.9 `reconciliation-spring-boot-starter` main: 11 changesets renumbered `0001..0011`, closing
      the `004b` hole per design D4. Verify: `mvn -pl :reconciliation-spring-boot-starter -am verify`
      and `scripts/check_migrations.sh`.
- [x] 3.10 `reconciliation-spring-boot-starter` test: `db/changelog/test/test-changelog.xml` becomes
      an includes-only root over `0001-create-local-order.sql` with id
      `reconciliation-test-0001-create-local-order` and author `ludwig-reconciliation-test` per
      design D3. The path named by `spring.liquibase.change-log` in
      `src/test/resources/application.properties` does not change. Verify:
      `mvn -pl :reconciliation-spring-boot-starter -am verify` and `scripts/check_migrations.sh`.
- [x] 3.11 `user-settings-spring-boot-starter`: 5 changesets → `0001..0005`, **prefix renamed**
      `usrset-` → `user-settings-` per design D3. `0005-user-consent-update-guard` keeps its plpgsql
      `$$` body under `splitStatements:false` and its two-statement rollback under
      `rollbackSplitStatements:false`, and both rationale paragraphs survive as `--` prose. Leave the
      deliberately-empty test master as an includes-only root with its comment verbatim. Verify:
      `mvn -pl :user-settings-spring-boot-starter -am verify` (its `OwnerModeIntegrationTest` applies
      the guard) and `scripts/check_migrations.sh`.

## 4. The services

- [x] 4.1 `crud-service-example`: flatten the four XML files under `db/changelog/changes/` into
      eight SQL files `0001..0008` per design D4, resolving the duplicate `003`/`004` numbering.
      Rewrite `db.changelog-master.xml`'s four own-module includes to the new `.sql` filenames,
      leaving its four foreign includes and their comments exactly where they are. Verify:
      `mvn -pl :crud-service-example verify` and `scripts/check_migrations.sh`.
- [x] 4.2 `notification-service`: flatten the six XML files into seventeen SQL files `0001..0017`
      per design D4, with file 0004's three changesets renamed from the `0004-N-` /
      `notification-service` scheme to `notification-0013..0015` / `ludwig-notification` per D3.
      Rewrite `db.changelog-master.xml`'s own-module includes, and keep `job-core`'s include above
      `0016-drop-lock-table` and `idempotency`'s above `0017-drop-idempotency`, with both ordering
      comments carried across verbatim and their changeset references renumbered. Verify:
      `mvn -pl :notification-service verify` and `scripts/check_migrations.sh`.
- [x] 4.3 Confirm the whole tree now conforms. Verify: `scripts/check_migrations.sh` exits 0 and
      reports 16 roots and 84 changesets verified — 16 rather than the 13 this task originally
      said, because `audit`, `user-settings` and `reconciliation` each ship a test-scoped root
      beside their main one, and the checker scopes per source set.

## 5. Wire the rule into the harness

- [x] 5.1 Append `scripts/check_migrations.sh` to `scripts/gate.sh`'s `COMMANDS` and add it to the
      comment block that explains what each gate command owns. Verify:
      `scripts/gate.sh --list job-core` prints `scripts/check_migrations.sh`.
- [x] 5.2 State the migration rule in `CLAUDE.md` under module conventions — formatted SQL for every
      changeset, includes-only XML roots, filename ⟺ id, mandatory explicit rollback, and the
      checker that enforces it — and mirror it in `openspec/config.yaml`'s `context` block, keeping
      that block shorter than the `CLAUDE.md` text as its header requires. Verify:
      `openspec validate unify-sql-migrations` and `grep -c check_migrations CLAUDE.md
      openspec/config.yaml` both non-zero.
- [x] 5.3 Add `docs/harness-enforcement.md` rows for the new checks, marking which are mechanically
      enforced versus written-down-only. Verify: `grep -n check_migrations docs/harness-enforcement.md`.

## 6. Documentation, in both locales

- [x] 6.1 Update `README.md` **and** `README.ru.md` for every module whose changelog moved, wherever
      they name a changelog filename or show an `<include>` example — `job-core`,
      `file-ingest-spring-boot-starter`, `outbox-spring-boot-starter`,
      `reconciliation-spring-boot-starter`, `pat-spring-boot-starter`,
      `user-settings-spring-boot-starter`. Verify:
      `grep -rn "changelog" sources/*/README*.md | grep -v "\.sql\|-changelog.xml"` returns no stale
      filename, and both locales of each README mention the same files.
- [x] 6.2 Regenerate `PROJECT_INDEX.md` and `project-index.json` so the listed changelog filenames
      are current. Verify: `scripts/manifest.sh stale` exits 0.
- [x] 6.3 Add the entry to `## [Unreleased]` in `CHANGELOG.md` **and** `CHANGELOG.ru.md` under
      `Changed`, written for a platform consumer: every changeset is now formatted SQL, ids and
      authors are namespaced per module, four prefixes were renamed, and a database that already
      applied the XML changesets needs `liquibase clearChecksums` plus a `DATABASECHANGELOG` id
      update for the renamed groups (design Risks). Verify: both files carry the same `##` heading
      set — `diff <(grep '^##' CHANGELOG.md) <(grep '^##' CHANGELOG.ru.md)` is empty.

## 7. The verification gate, in full

- [ ] 7.1 Run the gate for every touched module. Verify: `scripts/gate.sh --change
      unify-sql-migrations job-core outbox-spring-boot-starter idempotency-spring-boot-starter
      identity-projection-spring-boot-starter audit-spring-boot-starter export-spring-boot-starter
      file-action-spring-boot-starter file-ingest-spring-boot-starter pat-spring-boot-starter
      reconciliation-spring-boot-starter user-settings-spring-boot-starter crud-service-example
      notification-service` exits 0, with the real output quoted.
- [ ] 7.2 Run the aggregate build, because the converted changelogs are resources every module's
      tests load and the per-module gate does not prove the reactor is whole. Verify:
      `mvn clean install` exits 0 and `python3 scripts/check_aggregate_report.py` exits 0.
- [ ] 7.3 Write `openspec/changes/unify-sql-migrations/receipt.json` per `docs/agent-state.md` — the
      model, modules touched, real gate results, test counts, retries, the rollback commit, and
      anything unresolved. Verify: `openspec validate unify-sql-migrations` exits 0 and the receipt
      parses as JSON.
