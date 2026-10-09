## Why

Thirteen modules ship Liquibase changelogs and every one of them is XML, so each table definition
is written twice — once as Liquibase's `<createTable>` vocabulary and once, mentally, as the
Postgres DDL it generates. Forty-six of the eighty-four changesets already give up and wrap a raw
`<sql>` block, which means the repository is *already* half SQL and half XML with no rule saying
which to use. A reviewer cannot read a changelog and know what will reach the database, and two
modules describing the same shape describe it differently.

There is no mechanical check on any of this: changelog files are resources, so neither Checkstyle
(source text) nor ArchUnit (bytecode) can see them, and nothing fails the build when a new module
invents its own layout, forgets to namespace its changeset ids, or omits a rollback.

## What Changes

- **BREAKING (for consumers running these migrations against an existing database)**: every
  changeset moves from XML to Liquibase formatted SQL. Changeset ids and authors are preserved
  exactly, but the checksum of every changeset changes, so a database that has already applied
  them will fail validation. This change assumes no such database exists yet; see
  `design.md` for the recovery procedure a consumer needs if one does.
- All 84 changesets across 13 modules are rewritten as formatted SQL (`--liquibase formatted sql`),
  one `.sql` file per logical migration, under a fixed per-module layout.
- The root/master changelog of each module stays XML and is reduced to `<include>` elements and
  comments only — it may no longer carry a changeset. This is forced, not chosen: Liquibase
  4.27.0's formatted-SQL parser has no `--include` directive (verified by disassembling
  `AbstractFormattedChangeLogParser`; the recognised directives are `changeset`, `comment`,
  `rollback`, `preconditions`/`precondition-*`, `property`, `validChecksum`, `ignoreLines`).
- A new `database-migration` capability states the golden layout: file naming, changeset id and
  author namespacing, mandatory rollback, Postgres-only dialect, where preconditions are allowed,
  and the includes-only rule for roots.
- A new `scripts/check_migrations.sh` + `scripts/check_migrations.py` checks all of it mechanically
  and is added to `scripts/gate.sh`'s `COMMANDS`, beside `check_image_pins.sh`.
- `enforcement-triad` gains an explicit fourth owner — repository scripts run from the gate — for
  facts that live outside Java bytecode and Java source text. This records what the repository
  already does (`scripts/manifest.sh layout`, `scripts/check_image_pins.sh`) rather than
  introducing it.
- Every root changelog keeps its current path and filename, so the eleven
  `*LiquibaseAutoConfiguration` path constants, the two services' `application.yml`,
  `reconciliation-spring-boot-starter`'s test `application.properties` and every consumer
  `<include>` line are left alone. Only the affected modules' `README.md` + `README.ru.md` and
  `PROJECT_INDEX.md` change, where they name a changelog *file* rather than a root.

## Capabilities

### New Capabilities
- `database-migration`: the one shape every Liquibase changelog in this repository takes — formatted
  SQL for every changeset, an includes-only XML root per module, namespaced changeset ids, a
  mandatory rollback, and the mechanical check that fails the build on a violation.

### Modified Capabilities
- `enforcement-triad`: the "exactly one of three tools" requirement is widened to name a fourth
  owner, gate scripts, for checks on files that are neither Java bytecode nor Java source text.
  Two such checks already exist, so today the spec and the code disagree and the code is right.
- `data-access`: one scope clarification — the QueryDSL-only requirement governs repository queries
  in Java main sources and SHALL NOT be read as forbidding SQL in a Liquibase changelog, which is
  now the only form a changeset may take.

## Impact

**Modules whose changelogs are rewritten** (tier from `scripts/manifest.sh module <path>`):

| Module | Tier | In-repo dependents (gate targets) |
|---|---|---|
| `job-core` | library | `audit-spring-boot-starter`, `export-spring-boot-starter`, `file-action-spring-boot-starter`, `file-ingest-spring-boot-starter`, `idempotency-spring-boot-starter`, `notification-service`, `outbox-spring-boot-starter`, `reconciliation-spring-boot-starter` |
| `outbox-spring-boot-starter` | starter | `audit-spring-boot-starter`, `crud-service-example`, `export-spring-boot-starter`, `file-action-spring-boot-starter`, `file-ingest-spring-boot-starter`, `notification-service`, `user-settings-spring-boot-starter` |
| `idempotency-spring-boot-starter` | starter | `file-action-spring-boot-starter`, `messaging-spring-boot-starter`, `notification-service` |
| `identity-projection-spring-boot-starter` | starter | `crud-service-example`, `notification-service` |
| `audit-spring-boot-starter` | starter | `user-settings-spring-boot-starter` |
| `export-spring-boot-starter` | starter | `crud-service-example` |
| `file-action-spring-boot-starter` | starter | `crud-service-example` |
| `user-settings-spring-boot-starter` | starter | `notification-service` (provided) |
| `file-ingest-spring-boot-starter` | starter | none |
| `reconciliation-spring-boot-starter` | starter | none |
| `pat-spring-boot-starter` | starter | none |
| `crud-service-example` | service | none |
| `notification-service` | service | none |

**Other affected areas**

- `scripts/gate.sh` — one entry appended to `COMMANDS`.
- `scripts/check_migrations.sh`, `scripts/check_migrations.py` — new.
- `openspec/specs/` — one new capability, two modified.
- `CLAUDE.md` and `openspec/config.yaml` — the migration rule stated once in each.
- `CHANGELOG.md` + `CHANGELOG.ru.md` under `## [Unreleased]`.

**Not affected**

- No POM changes: Liquibase's version comes from `spring-boot-dependencies` and no plugin is added.
  `scripts/manifest.sh build` is therefore not required and `PROJECT_INDEX.md` changes only in the
  changelog filenames it lists.
- No Java changes at all. Because every root changelog keeps its path, the eleven
  `*LiquibaseAutoConfiguration` classes are not touched, and no bean, property or signature moves.

## Non-goals

- **Not** consolidating the per-module `DATABASECHANGELOG` histories into one. Each module keeping
  its own changelog applied by its own `SpringLiquibase`, or folded into a service's master by an
  `<include>`, is a deliberate existing arrangement — `notification-service`'s master changelog
  explains at length why `user-settings` is deliberately *not* included — and this change rewrites
  the files without touching that topology.
- **Not** changing any table, column, index or constraint. Every converted changeset must produce
  the same schema; the conversion is verified against the pre-change schema, not re-designed.
- **Not** introducing a Liquibase Maven plugin, a `liquibase.properties`, or a `validate`-phase
  Liquibase goal. The checker is a repository script, consistent with `check_image_pins.sh`.
- **Not** adding `--validChecksum` lines. The decision on record is that no deployed database has
  applied these changesets, so the converted files stay clean; the fallback is documented in the
  design rather than encoded in the files.
- **Not** exempting the three test-scoped changelogs (`audit-spring-boot-starter` and
  `user-settings-spring-boot-starter` each have a `db.changelog-master.xml` under
  `src/test/resources`, `reconciliation-spring-boot-starter` a `test/test-changelog.xml`). They are
  in scope and follow the identical layout; there is no test-only exemption, because a test fixture
  that may ignore the rule is the place the rule stops being learned.
