## Context

See `proposal.md` — Why. The constraints that actually shape the approach, each established by
reading the resolved Liquibase 4.27.0 jar rather than the documentation:

1. **Formatted SQL cannot include.** `liquibase.parser.AbstractFormattedChangeLogParser` recognises
   `changeset`, `comment`, `rollback`, `preconditions`, `precondition-*`, `property`,
   `validChecksum` and `ignoreLines`. There is no `include` or `includeAll`. Every root changelog
   therefore stays XML.
2. **Formatted SQL supports three precondition types**, and only three:
   `FormattedSqlChangeLogParser` dispatches `sql-check`, `table-exists` and `view-exists` and raises
   `'<type>' precondition type is not supported.` otherwise. The repository uses `tableExists` (5
   occurrences) and `sqlCheck` (2), so it fits. The attribute spelling is `table:<name>`, not
   `tableName:` — verified by parsing both; `tableName:` raises
   `Table name was not specified correctly in tableExists precondition.`
3. **An explicitly-empty rollback is expressible.** `--rollback NOT REQUIRED` and
   `--rollback empty` both parse to Liquibase's `EmptyChange`, the same object XML's `<rollback/>`
   produces.
4. **Prose comments pass through untouched.** `--` lines that match no directive, and `/* */`
   blocks, parse without warning and arrive in the SQL body as SQL comments. A `$$ ... $$` plpgsql
   body with embedded `;` survives verbatim under `splitStatements:false`, and consecutive
   `--rollback` lines concatenate into one statement block.
5. **`--comment` holds one line only.** Two `--comment` directives on one changeset leave the
   second; the first is discarded.

Current state: 26 changelog files, 84 changesets, 13 modules, all XML. 46 changesets already wrap a
raw `<sql>`. No POM references Liquibase — the version comes from `spring-boot-dependencies`.

Decision on record from the user: **no deployed database has applied these changesets**, so the
conversion does not need to preserve checksums.

## Goals / Non-Goals

**Goals:**

- One authoring format for a changeset, with no judgement call left to the author.
- A changeset's identity visible in its filename, so `DATABASECHANGELOG` and the tree agree.
- Every invariant in the `database-migration` capability checked by a script the gate runs.
- The existing include topology preserved exactly, including the orderings `notification-service`'s
  master changelog documents as load-bearing.

**Non-Goals** (design-level, beyond the proposal's):

- No change to any root changelog's *path or filename*. Consumers' masters, the eleven
  `*LiquibaseAutoConfiguration` path constants, two `application.yml` files and several READMEs name
  those paths; keeping them fixed removes that entire class of edit from the change and keeps the
  diff to the migration content and the new checker.
- No attempt to make the checker understand SQL. It checks structure, naming, directives and include
  wiring — never whether the DDL is correct. Schema equivalence is established by the integration
  tests, which build the schema from these changelogs against a `PostgreSQLContainer`.

## Decisions

### D1: Root changelogs stay XML and become includes-only; every changeset moves to `.sql`

Forced by constraint 1. The alternative — YAML roots — buys nothing: YAML can include, but then the
repository has three formats instead of two and the root still is not SQL. The honest framing, which
the spec states, is that "all migrations are SQL" means *all changesets*, and the root is wiring
rather than a migration.

The two deliberately-empty test masters in `audit-spring-boot-starter` and
`user-settings-spring-boot-starter` already satisfy this requirement with zero includes, and their
long explanatory comments (why they exist rather than `spring.liquibase.enabled=false`) are kept
verbatim.

### D2: One changeset per file, filename and id numerically identical

The invariant `file NNNN-<slug>.sql ⟺ changeset <prefix>-NNNN-<slug>` is what makes almost every
other rule checkable with no lookup table. It costs 84 small files where there were 26 larger ones.

Alternative considered: keep one file per module and check only the ids. Rejected — the strongest
available check would then be "ids ascend inside a file", and the thing that actually goes wrong in
this repository already went wrong under that regime: `crud-service-example` carries two changesets
numbered `003` and two numbered `004`, in different files, which collide only in a reader's head
because Liquibase keys on the filename too.

### D3: Changeset prefix normalised to the module, author always `ludwig-<prefix>`

Deriving the author from the prefix is what lets the checker verify namespacing without a
13-entry table it could drift from. Three modules need renaming to satisfy it:

| Module | Old prefix / author | New prefix / author | Why |
|---|---|---|---|
| `file-ingest-spring-boot-starter` | `ingest-` / `ludwig-file-ingest` | `file-ingest-` / `ludwig-file-ingest` | prefix and author already disagreed |
| `user-settings-spring-boot-starter` | `usrset-` / `ludwig-user-settings` | `user-settings-` / `ludwig-user-settings` | `usrset` is an abbreviation of nothing else in the repo |
| `notification-service` (file 0004) | `0004-N-` / `notification-service` | `notification-` / `ludwig-notification` | one file used a different scheme from its five siblings |
| `reconciliation-spring-boot-starter` (test) | `test-` / `ludwig-reconciliation-test` | `reconciliation-test-` / `ludwig-reconciliation-test` | author already carried the real namespace |

### D4: Renumbering, module by module

Numbers are reassigned to be unique and ascending in include order. The full mapping, which the
tasks implement one module per task:

- **`job-core`** (`db/changelog/job-core/`): `001-create-run-lock` → `0001-create-run-lock`.
- **`outbox`**: `001..005` → `0001..0005`, slugs unchanged.
- **`idempotency`**: `001-create-claim`, `002-migrate-notification-idempotency` → `0001`, `0002`.
- **`identity`**: `001..007` → `0001..0007`, slugs unchanged.
- **`audit`**: `001..004` → `0001..0004`, slugs unchanged.
- **`export`**: `001..014` → `0001..0014`, slugs unchanged.
- **`file-action`**: `001..002` → `0001..0002`, slugs unchanged.
- **`file-ingest`**: `001..004` → `0001..0004`, slugs unchanged, prefix per D3.
- **`pat`**: `001..003` → `0001..0003`, slugs unchanged.
- **`user-settings`**: `001..005` → `0001..0005`, slugs unchanged, prefix per D3.
- **`reconciliation`**: closes the `004b` hole —
  `001→0001`, `002→0002`, `003→0003`, `004→0004`, `004b-index-inbox-fetch-suppression→0005`,
  `005-create-task-state→0006`, `006-create-remote-job→0007`, `007-index-remote-job→0008`,
  `008-create-quota-lease→0009`, `009-create-quota-waiter→0010`, `010-create-audit-record→0011`.
- **`reconciliation` (test)**: `test-001-create-local-order` →
  `reconciliation-test-0001-create-local-order` at `db/changelog/test/0001-create-local-order.sql`.
- **`crud-service-example`** (`db/changelog/changes/`), flattening four XML files into eight SQL
  files and resolving the duplicate numbering:
  `product-category→0001`, `product→0002`, `product-indexes→0003`, `reference-categories→0004`,
  `product-supplier-partner→0005`, `product-scope-indexes→0006`, `product-watcher→0007`,
  `product-watcher-index→0008`.
- **`notification-service`** (`db/changelog/changes/`), flattening six XML files into seventeen SQL
  files: `request→0001`, `delivery→0002`, `delivery-indexes→0003`, `delivery-status-history→0004`,
  `delivery-content→0005`, `recipient-profile→0006`, `recipient-preference→0007`,
  `suppression→0008`, `idempotency→0009`, `lock→0010`, `rate-limit-window→0011`,
  `template-revision→0012`, `record-quiet-hours-deferral→0013`, `drop-recipient-preference→0014`,
  `drop-recipient-profile→0015`, `drop-lock-table→0016`, `drop-idempotency→0017`.

The master changelogs' interleaved foreign includes keep their exact positions: in
`notification-service`, `job-core`'s root above `0016-drop-lock-table` and `idempotency`'s root
above `0017-drop-idempotency`, for the two reasons that file already documents at length. Those
comments move across verbatim and their references are renumbered with the files.

### D5: Rationale comments become `--` prose; `--comment` carries a one-line summary

Constraint 5 rules out putting a multi-paragraph rationale in `--comment`. These comments are the
most valuable content in the changelogs — `user-settings`' append-only trigger carries two
paragraphs on why the guard is in the database and not only in `SnapshotImmutabilityListener`, and
why it is `UPDATE` and deliberately not `DELETE`.

So: the rationale is kept verbatim as `--` lines above the `--changeset` line, and `--comment`
carries one line. The trade-off is that `DATABASECHANGELOG.COMMENTS` holds only the summary where it
previously held the whole `<comment>` body. Accepted: the file is where the rationale is read, and no
tooling in this repository reads that column.

### D6: `dbms:postgresql` on every changeset

Every integration test that applies these changelogs does so against a `PostgreSQLContainer` (17
such tests); nothing runs them on H2. Declaring the dialect per changeset puts the constraint at the
statement instead of in a header comment that the next appended changeset sits below.

The failure mode to be aware of: if these migrations are ever pointed at a non-Postgres database,
changesets will be silently *skipped* rather than failing. That is strictly better than today, where
they would fail with a syntax error halfway through, leaving a partial schema.

### D7: The checker is a gate script, and `enforcement-triad` is corrected to say so

A changelog is a resource file: invisible to ArchUnit (bytecode) and not Java source text
(Checkstyle). The repository already has two checks in this position — `scripts/manifest.sh layout`
and `scripts/check_image_pins.sh` — so the triad spec's "exactly one of three tools" is already
contradicted by the code. Per `openspec/config.yaml`'s specs rule, the code is the truth and the
disagreement is a finding; the delta names gate scripts as a fourth owner and keeps "exactly one
owner" across all four.

`scripts/check_migrations.sh` + `scripts/check_migrations.py` mirrors `check_image_pins`'s shape
exactly: a shell wrapper that checks `python3`/`git` are present, supports `--list`, enumerates
tracked files via `git ls-files`, and pipes them to a Python checker. Using `git ls-files` rather
than `find` is what keeps `target/classes/db/changelog/**` — a copy of every one of these files —
out of the results.

Checks implemented, each mapping to a scenario in the `database-migration` spec: no `<changeSet>` in
any XML under `db/changelog/`; `--liquibase formatted sql` header; exactly one `--changeset` per
file; filename ⟺ id agreement; one prefix and `ludwig-<prefix>` author per root; number uniqueness;
include order versus numeric order for the owning module's files; `--rollback` present; `dbms:postgresql`
present; precondition types limited to the three supported; one pinned `dbchangelog` XSD version
across roots; every `<include>` resolves to a file that exists.

### D8: Dependency direction and the three-POM split

**Dependency-direction check.** *Does any module this change touches gain an in-repo dependency on
another?* No. The change rewrites resource files under `db/changelog/` and adds two files under
`scripts/`. No `<dependency>` element is added, removed or rescoped in any POM, so the reactor DAG
is unchanged and no cycle is possible. The eleven `*LiquibaseAutoConfiguration` classes are not
edited at all, because D1's non-goal keeps every root changelog's path fixed — the only Java-visible
coordinate they name.

**Which of the three POMs changes.** None. `pom.xml` (root) is untouched because no plugin version
or library build decision changes; `build/ludwig-bom/pom.xml` is untouched because Liquibase's
version comes from `spring-boot-dependencies` and no third-party dependency is added;
`build/ludwig-service-parent/pom.xml` is untouched because no service build decision changes. The
checker is a repository script invoked by `scripts/gate.sh`, deliberately not a Maven plugin
execution, for the same reason `check_image_pins.sh` is not one: it checks tracked files across the
whole tree rather than one module's build output, so there is no module whose lifecycle it belongs
to.

### D9: No `scripts/manifest.sh build`

No POM changes (D8), so the manifest does not need rebuilding for dependency or tier reasons.
`PROJECT_INDEX.md` changes only where it lists changelog filenames, which is a regeneration of the
index rather than a manifest rebuild. `scripts/manifest.sh stale` must still exit 0 before archive.

## Risks / Trade-offs

- **A converted changeset silently produces a different schema.** → The integration tests build the
  schema from these changelogs against real Postgres, so a wrong column type or a dropped constraint
  fails `verify`. Beyond that, the conversion is done one module per task with the old XML in the
  diff beside the new SQL, and the task's verification is that module's `verify` plus every in-repo
  dependent's — which for `job-core` and `outbox-spring-boot-starter` is eight and seven modules
  respectively.
- **The checker's include-order rule misfires on a deliberately interleaved foreign include.** →
  The rule is scoped to files belonging to the module that owns the root; a foreign include is
  skipped, with `notification-service` as the test case that proves it, since it is the only root
  that interleaves.
- **A consumer outside this repository has already applied the XML changesets.** → Their next
  startup fails with a checksum mismatch, not a corrupt schema. The recovery is
  `liquibase clearChecksums` followed by a normal `update`, because the ids and authors are
  preserved for every changeset except the four groups renamed in D3 — for those, the
  `DATABASECHANGELOG` rows must be updated to the new id, author and filename, or the changeset will
  re-run. This is documented in the CHANGELOG entry rather than mitigated in the files, per the
  user's decision that no such database exists.
- **84 files where there were 26.** → Accepted; it is what makes the filename-is-the-id invariant
  available, and each file is small and self-describing.
- **`DATABASECHANGELOG.COMMENTS` loses the full rationale.** → Accepted, D5.
- **A non-Postgres target now skips rather than fails.** → Accepted, D6; the skip is safer than the
  partial schema it replaces.

## Migration Plan

Per-module, in reactor dependency order so that a dependent is never verified against an
unconverted dependency: `job-core` → `outbox` → `idempotency` → `identity` → `audit` → `export` →
`file-action` → `file-ingest` → `pat` → `reconciliation` → `user-settings` → `crud-service-example`
→ `notification-service`. The checker lands first, so every subsequent module's task has it
available; it is written to pass on an unconverted tree only in `--list` mode, and the gate entry is
added in the final task group once the tree conforms.

Rollback strategy for the change itself: each module's conversion is a self-contained commit, and
`git revert` of one restores that module's XML without touching the others. There is no database
state to undo, because the change alters only how migrations are authored.
