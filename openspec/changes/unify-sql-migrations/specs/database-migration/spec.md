## Purpose

Every Liquibase migration in this platform has one shape: a changeset is PostgreSQL in a formatted
SQL file, one changeset per file, named so that the filename and the changeset id cannot disagree,
and an XML root changelog that does nothing but include them in order. Thirteen modules ship
migrations and a reader must be able to open any of them and see the DDL that will reach the
database, not a vocabulary that generates it.

## ADDED Requirements

### Requirement: A changeset is authored in Liquibase formatted SQL

Every Liquibase changeset in this repository SHALL be written in Liquibase formatted SQL — a file
whose first non-blank line is `--liquibase formatted sql`. No changeset SHALL be authored in
Liquibase's XML, YAML or JSON change vocabulary (`<createTable>`, `<addColumn>`, `<createIndex>`,
`<insert>`, and the rest).

The DDL that reaches PostgreSQL is then the text in the file. This is not a style preference: 46 of
the 84 changesets that existed before this requirement already wrapped a raw `<sql>` block, so the
repository already expressed most of its schema as SQL while giving no rule for when to do so.

#### Scenario: A changeset is added in the XML change vocabulary

- **WHEN** a file under a module's `src/{main,test}/resources/db/changelog/` contains a
  `<changeSet>` element
- **THEN** `scripts/check_migrations.sh` exits non-zero naming the file, and the verification gate
  fails

#### Scenario: A SQL file omits the format header

- **WHEN** a `.sql` file under `db/changelog/` does not begin with `--liquibase formatted sql`
- **THEN** `scripts/check_migrations.sh` exits non-zero naming the file, because Liquibase would
  parse it as a single unnamed changeset with no id, author or rollback

### Requirement: The root changelog is XML and contains includes only

Each module SHALL have exactly one root changelog under `db/changelog/`, it SHALL be XML, and it
SHALL contain only `<include>` elements, comments and the `<databaseChangeLog>` wrapper. A root
changelog SHALL NOT contain a `<changeSet>`.

The root stays XML because Liquibase 4.27.0's formatted-SQL parser recognises no include directive;
its directives are `changeset`, `comment`, `rollback`, `preconditions`/`precondition-*`, `property`,
`validChecksum` and `ignoreLines`. A root with no includes at all is valid and is how a library's
test fixture stands in for a consuming application's changelog.

#### Scenario: A root changelog declares a changeset directly

- **WHEN** a changeset is added to a `db.changelog-master.xml` or a `<key>-changelog.xml` rather
  than to its own SQL file
- **THEN** `scripts/check_migrations.sh` exits non-zero naming the file

#### Scenario: A root changelog references a file that does not exist

- **WHEN** an `<include file="classpath:db/changelog/...">` names a path with no corresponding file
  in the module's resources
- **THEN** `scripts/check_migrations.sh` exits non-zero naming the include, rather than the failure
  first appearing as a failed migration at service startup

### Requirement: One changeset per file, and the filename carries its identity

A SQL changelog file SHALL contain exactly one changeset. The file SHALL be named
`NNNN-<slug>.sql`, where `NNNN` is a four-digit zero-padded sequence number and `<slug>` is
lower-case kebab-case. The changeset's id SHALL be `<prefix>-NNNN-<slug>`, with `NNNN` and `<slug>`
identical to the filename's.

A filename and an id that can drift are two names for one thing, and the one that appears in
`DATABASECHANGELOG` is the one nobody reads until an incident.

#### Scenario: A file holds more than one changeset

- **WHEN** a SQL changelog file contains two `--changeset` directives
- **THEN** `scripts/check_migrations.sh` exits non-zero naming the file and both changeset ids

#### Scenario: The changeset id does not match its filename

- **WHEN** `0007-index-output-run.sql` declares `--changeset ludwig-export:export-0008-index-output`
- **THEN** `scripts/check_migrations.sh` exits non-zero reporting the expected id
  `export-0007-index-output-run` against the declared one

### Requirement: Changeset ids and authors are namespaced per module and unique within it

All changesets reachable from one module's root changelog SHALL share a single id prefix, and the
author of every one of them SHALL be `ludwig-<prefix>`. The sequence number SHALL be unique within
the module and SHALL ascend in the order the root changelog includes the files.

A library's changelog is applied into a consuming application's `DATABASECHANGELOG`, so an
un-namespaced id is a collision waiting for the one consumer that happens to pick the same name.
Uniqueness and ascent are what make the number mean something: before this requirement
`crud-service-example` had two changesets numbered `003` and two numbered `004`, which collided
only in a reader's head because Liquibase keys on the filename as well, and
`reconciliation-spring-boot-starter` had an id numbered `004b`.

#### Scenario: A module mixes two authors

- **WHEN** one changeset in a module declares author `notification-service` while its siblings
  declare `ludwig-notification`
- **THEN** `scripts/check_migrations.sh` exits non-zero reporting the two authors found and the
  module

#### Scenario: A sequence number repeats within a module

- **WHEN** two SQL files reachable from one root both carry the number `0003`
- **THEN** `scripts/check_migrations.sh` exits non-zero naming both files

#### Scenario: Include order disagrees with sequence order

- **WHEN** a root changelog includes `0005-...` before `0004-...` among its own module's files
- **THEN** `scripts/check_migrations.sh` exits non-zero reporting the position, because a reader who
  trusts the numbering to be the application order would be wrong about when a column exists

#### Scenario: A module's include of another module's changelog is interleaved deliberately

- **WHEN** a service's root changelog includes a starter's root changelog between two of its own
  numbered files — as `notification-service` does to guarantee `job_run_lock` exists before
  `notification_lock` is dropped, and that idempotency rows are moved before the old table goes
- **THEN** the ordering check applies only to the files of the module that owns the root, and the
  interleaved foreign include is not a violation

### Requirement: Every changeset declares its rollback explicitly

Every changeset SHALL carry a `--rollback` directive. Where reversing the change is genuinely not
required, the directive SHALL be `--rollback NOT REQUIRED`, which Liquibase parses to the same empty
rollback as XML's `<rollback/>`.

The point is not that every migration is reversible; it is that the author decided and recorded the
decision. Before this requirement 37 of 84 changesets had no rollback and nothing distinguished
"irreversible by nature" from "nobody thought about it".

#### Scenario: A changeset has no rollback directive

- **WHEN** a changeset declares neither rollback SQL nor `--rollback NOT REQUIRED`
- **THEN** `scripts/check_migrations.sh` exits non-zero naming the changeset id

### Requirement: Changesets declare the PostgreSQL dialect

Every changeset SHALL declare `dbms:postgresql`. This platform's migrations are PostgreSQL-only —
every integration test that runs them does so against a `PostgreSQLContainer`, and several
changesets use `ON CONFLICT`, `TIMESTAMP WITH TIME ZONE` defaults and partial indexes that are not
portable.

Declaring it on the changeset puts the constraint where the statement is, rather than in a comment
at the top of a file that a later changeset is appended below.

#### Scenario: A changeset omits the dialect

- **WHEN** a `--changeset` line carries no `dbms:postgresql`
- **THEN** `scripts/check_migrations.sh` exits non-zero naming the changeset id

### Requirement: Only table-exists, view-exists and sql-check preconditions are used

A changeset's preconditions SHALL be expressed with `--preconditions`, `--precondition-table-exists
table:<name>`, `--precondition-view-exists view:<name>` and `--precondition-sql-check
expectedResult:<r> <sql>`. No other precondition type SHALL be used.

This is a limit of the format, not a choice: Liquibase 4.27.0's formatted-SQL parser supports
exactly those three precondition types and raises `'<type>' precondition type is not supported.` for
anything else. It is stated here so the limit is discovered while writing the spec rather than at
the first migration that needs a `columnExists`.

#### Scenario: A guarded changeset ships to consumers that may not have the legacy table

- **WHEN** a changeset moves rows out of a table that only some consumers have — as
  `audit-spring-boot-starter` does for `user_setting_audit` and `sync_audit_record`, and
  `idempotency-spring-boot-starter` for `notification_idempotency`
- **THEN** it declares `--preconditions onFail:MARK_RAN` with
  `--precondition-table-exists table:<legacy table>`, and a consumer without the table records the
  changeset as run rather than failing its startup

### Requirement: The XML roots pin one dbchangelog schema version

Every root changelog SHALL declare the same `dbchangelog` XSD version, and it SHALL be the one
matching the Liquibase that `spring-boot-dependencies` resolves. Before this requirement the roots
split 14 files on `dbchangelog-4.20.xsd` against 12 on `dbchangelog-4.27.xsd`, which tells a reader
nothing except that two people added files at different times.

#### Scenario: A root declares a different XSD version from its siblings

- **WHEN** a root changelog's `xsi:schemaLocation` names a `dbchangelog-<v>.xsd` other than the
  pinned version
- **THEN** `scripts/check_migrations.sh` exits non-zero reporting the version found and the version
  expected

### Requirement: The migration layout is checked by a gate script, not by the triad

The checks in this capability SHALL be implemented as `scripts/check_migrations.sh` (with
`scripts/check_migrations.py`) and SHALL be listed in `scripts/gate.sh`'s `COMMANDS`, beside
`scripts/check_image_pins.sh`.

Neither ArchUnit nor Checkstyle can own them: a changelog is a resource file, so it is absent from
bytecode and is not Java source text. See the `enforcement-triad` capability, which names gate
scripts as the owner for exactly this class of fact.

#### Scenario: A developer wants the commands without running them

- **WHEN** `scripts/gate.sh --list <module>` is run
- **THEN** `scripts/check_migrations.sh` appears among the printed commands

#### Scenario: The checker is run directly on a clean tree

- **WHEN** `scripts/check_migrations.sh` is run on a tree that satisfies every requirement above
- **THEN** it exits 0 and prints the number of root changelogs and changesets it verified

#### Scenario: A reviewer wants to see the inventory rather than only violations

- **WHEN** `scripts/check_migrations.sh --list` is run
- **THEN** it prints every root changelog with its module, prefix, author and ordered changesets,
  and exits 0 regardless of violations, so the output can be read as an inventory
