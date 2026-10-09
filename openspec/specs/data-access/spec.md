# Data access

## Purpose
Data access in this platform is QueryDSL-JPA against generated Q-types, so that a query is
checked by the compiler and a renamed column breaks the build rather than production. A small
number of statements genuinely cannot be written that way, and those are the exception this
capability defines.

> **This spec describes the code as it is, which is not what `CLAUDE.md` says.** `CLAUDE.md`
> states that "two carve-outs from the QueryDSL-only rule exist". In the current tree, native SQL
> appears in **nine** main-source files across **six** modules, of which only the two named
> packages are mechanically fenced. The disagreement is recorded in the last requirement below
> rather than resolved here: the code is the truth, and reconciling the two is a change somebody
> has to decide on.

## Requirements

### Requirement: Queries are QueryDSL against generated Q-types
Repository queries SHALL be written in QueryDSL-JPA against generated Q-types. A change SHALL
NOT introduce a JPQL string or a Spring Data derived query method.

#### Scenario: A module needs a new query
- **WHEN** a change adds a repository query
- **THEN** it is a QueryDSL predicate against the generated Q-type, so that a renamed column or
  a changed type fails the compile rather than the request

#### Scenario: A change adds a derived query method
- **WHEN** a repository interface declares a method whose name Spring Data parses into a query
- **THEN** it is refused: the query is then expressed in a method name that nothing type-checks

### Requirement: A native statement carries javadoc saying why QueryDSL cannot express it
Every native SQL statement in main sources SHALL carry javadoc stating what QueryDSL-JPA cannot
express, because QueryDSL-JPA generates JPQL and JPQL lacks the construct.

#### Scenario: A statement needs ON CONFLICT or RETURNING
- **WHEN** a statement needs `ON CONFLICT`, `RETURNING`, `SKIP LOCKED`, `COPY`, an advisory lock,
  or a set-based merge — none of which JPQL has
- **THEN** it may be written as native SQL, and its javadoc says which construct is missing and
  why the QueryDSL alternative is not merely less tidy but wrong

#### Scenario: A statement is native only because it was easier to write
- **WHEN** a native statement's behaviour is expressible in QueryDSL
- **THEN** it is refused. The javadoc requirement exists so that this question is answered in
  writing at the point the statement is added

### Requirement: Two packages are mechanically fenced
`ru.ludwigandreas.ingest.bulk` in `file-ingest-spring-boot-starter` and
`ru.ludwigandreas.idempotency.sql` in `idempotency-spring-boot-starter` SHALL each confine their
module's SQL and JDBC, enforced by that module's own `SqlConfinementTest`.

#### Scenario: SQL appears elsewhere in file-ingest
- **WHEN** a SQL string or a JDBC type appears in `file-ingest-spring-boot-starter` outside
  `ru.ludwigandreas.ingest.bulk`
- **THEN** that module's `SqlConfinementTest` fails the build. The module's own run tables are
  queried through QueryDSL predicates like everything else

#### Scenario: SQL appears elsewhere in idempotency
- **WHEN** a SQL string or a JDBC type appears in `idempotency-spring-boot-starter` outside
  `ru.ludwigandreas.idempotency.sql`
- **THEN** that module's `SqlConfinementTest` fails the build. The reads, the three state
  transitions and the purge in that module are QueryDSL like everything else

#### Scenario: A statement in the file-ingest bulk package is reviewed
- **WHEN** the two statements there are read
- **THEN** they are Postgres `COPY` via `CopyManager`, which streams and is several times faster
  than batched `INSERT`, and the set-based `INSERT … SELECT … ON CONFLICT DO UPDATE` that merges
  a staging table into a target. The alternative is reading four million staged rows into the JVM
  to write them back one at a time, which is the behaviour that module exists to avoid

### Requirement: Native SQL outside the fenced packages is a known, unfenced gap
Native SQL currently exists in main sources outside the two fenced packages, in
`export-spring-boot-starter`, `job-core`, `notification-service` and
`reconciliation-spring-boot-starter`. Each such statement SHALL carry its justifying javadoc, and
no mechanical check currently enforces either the javadoc or the confinement for these modules.

#### Scenario: The current unfenced sites are enumerated
- **WHEN** main sources are searched for `PreparedStatement`, `createNativeQuery` or
  `nativeQuery = true`
- **THEN** the sites outside the fenced packages are
  `export/repository/ExportReportRunRepositoryCustomImpl.java` (a conditional reclaim update),
  `job/core/claim/SkipLockedClaim.java` and `job/core/lock/JdbcRunLock.java` (`SKIP LOCKED`
  claim and the lease statements),
  `notification/repository/{NotificationDeliveryRepository,RateLimitWindowRepository,TemplateRevisionRepository}.java`
  (claim-with-`RETURNING` and window upserts), and
  `reconciliation/quota/DatabaseQuota.java` (`pg_advisory_xact_lock`). All of them carry
  javadoc explaining the construct JPQL lacks

#### Scenario: A change adds native SQL to an unfenced module
- **WHEN** a change adds a native statement outside the two fenced packages
- **THEN** nothing fails the build, which is the gap. The change SHALL state in its design
  either why the statement belongs where it is, or that it is extending confinement to that
  module with a `SqlConfinementTest` of its own

### Requirement: A dynamic property path is built by the module that owns the vocabulary
QueryDSL's `PathBuilder` resolves a property path at runtime and is therefore the one construct in this
platform's data access that the compiler does not check. A repository SHALL NOT construct one to serve a
caller-supplied property path. Where a caller-supplied path must be resolved - the `$orderby` clause,
whose paths are strings by definition - the module that owns the path vocabulary SHALL perform the
resolution and SHALL expose the result, and the repository SHALL consume it.

#### Scenario: A repository needs to order by a path named in a request
- **WHEN** a repository must turn a caller's ordering clause into QueryDSL ordering expressions
- **THEN** it obtains them from the module that validated the paths, and contains no `PathBuilder`
  field, no segment-splitting loop and no raw-typed ordering construction. A repository that walks the
  path itself has re-implemented validated resolution without the validation, and has done it once per
  repository

#### Scenario: The same walk is written in two repositories
- **WHEN** two repositories each carry a private reflective path walk for the same purpose
- **THEN** that is a finding against this requirement regardless of whether either is wrong, because
  the walk's correctness condition - that it addresses the same query root and alias as the predicate -
  is invisible at each site and silently produces a cross join when broken

#### Scenario: A repository builds a path that no request named
- **WHEN** a repository needs a path that is fixed at compile time
- **THEN** it uses the generated Q-type, as the QueryDSL-only requirement already states. This
  requirement concerns only paths that arrive as strings from a caller

#### Scenario: How this is enforced
- **WHEN** this requirement is enforced
- **THEN** it is `architecture-rules`' `persistence.dynamic-paths-are-not-hand-built`, which forbids a
  dependency on QueryDSL's `PathBuilder` anywhere in the analysed packages - not only in the repository
  ones. There is no allowed package and no exemption list: the legitimate users are the library modules that
  own a caller-facing path vocabulary, and a service's analysis never imports their classes, so an exemption
  list could only ever be wrong in one direction

#### Scenario: The module that owns the vocabulary runs the rule against itself
- **WHEN** the module that legitimately builds the paths - `odata-filter-spring-boot-starter` - runs the
  shared rule library over its own packages
- **THEN** it disables this one rule, and that is not debt: the rule's own javadoc names that module as the
  one place the walk belongs. "Owns the vocabulary" is not a property bytecode carries, so it is recorded at
  the point of the disable rather than encoded in the rule

#### Scenario: The alias agreement cannot be checked statically
- **WHEN** the companion condition is considered - that the ordering expressions and the predicate address
  the same query alias
- **THEN** no static check can express it, because an alias is the *value* of a string a `Q`-type was
  constructed with. It is checked at runtime instead, by the executor, which refuses a mismatched root and
  names both aliases - and that assertion's javadoc says why it is where it is
