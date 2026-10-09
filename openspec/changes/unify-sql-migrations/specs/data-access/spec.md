## ADDED Requirements

### Requirement: The QueryDSL-only rule governs repository queries, not migrations

The requirement that queries are QueryDSL against generated Q-types SHALL be read as governing
**repository queries in Java sources**. It SHALL NOT be read as applying to a Liquibase changelog,
where SQL is now the only permitted form a changeset may take — see the `database-migration`
capability.

The two are not in tension and the distinction is the reason: the QueryDSL rule exists so a renamed
column breaks the build rather than production, which requires the query to be compiled against
generated Q-types. A migration is the statement that renames the column. It has no Q-type to compile
against, it is applied once in a recorded order, and Liquibase checksums it — so the protection the
QueryDSL rule buys does not apply to it, and the XML change vocabulary that would be the
alternative buys nothing in exchange for hiding the DDL.

#### Scenario: A changelog is rewritten into the XML change vocabulary to satisfy the QueryDSL rule

- **WHEN** a change moves a `CREATE TABLE` out of a `.sql` changelog into `<createTable>` on the
  grounds that the platform forbids SQL strings
- **THEN** that is a misreading of this requirement, `scripts/check_migrations.sh` fails the build,
  and the SQL form is the correct one

#### Scenario: A repository query is written as native SQL citing the migration rule

- **WHEN** a repository method is written as a native SQL string on the grounds that the platform's
  migrations are SQL
- **THEN** the QueryDSL-only requirement still applies in full, and the statement is permitted only
  inside one of the two fenced packages under the conditions those already carry
