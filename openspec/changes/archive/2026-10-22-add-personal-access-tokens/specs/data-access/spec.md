## ADDED Requirements

### Requirement: `pat-spring-boot-starter` admits no SQL at all, and the carve-outs stay at two

`pat-spring-boot-starter` SHALL contain no SQL string and no JDBC type in main sources, in any
package, enforced by its own `SqlConfinementTest`. This change SHALL NOT add a third fenced package
to the two named by the existing "Two packages are mechanically fenced" requirement.

This is asserted as a requirement rather than left as a silence because the instinct is to reach for
the idempotency carve-out. "Claim a token atomically" and "claim an idempotency key atomically" read
as the same problem and are not: the idempotency claim *writes* in order to claim, which is why it
needs `INSERT ... ON CONFLICT ... RETURNING` that JPQL cannot express. PAT verification only
*reads* - a point read on a unique `key_id` index, followed by a constant-time digest comparison in
the application. There is nothing for `ON CONFLICT` or `RETURNING` to do.

The confinement test takes the inverse shape of the other two: rather than fencing SQL into one
package, it asserts the module has none.

#### Scenario: SQL or JDBC appears anywhere in the PAT starter
- **WHEN** a SQL string, a `PreparedStatement`, a `createNativeQuery` call or a `nativeQuery = true`
  annotation appears anywhere in `pat-spring-boot-starter`'s main sources
- **THEN** that module's `SqlConfinementTest` fails the build, naming the class - including for the
  module's own tables, which are queried through QueryDSL predicates like everything else

#### Scenario: The verification query is reviewed
- **WHEN** the PAT verification read is read
- **THEN** it is a QueryDSL predicate against the generated Q-type selecting on `key_id`, which is
  unique and indexed, and the digest comparison happens in the application in constant time - not in
  SQL, because a SQL-side comparison is neither constant-time nor expressible in QueryDSL

#### Scenario: A later change wants native SQL in the PAT starter
- **WHEN** a change proposes a native statement in `pat-spring-boot-starter`
- **THEN** the inverse `SqlConfinementTest` fails, and the change must argue for a third carve-out in
  its design against the conditions the existing two meet, rather than adding one by editing a test
