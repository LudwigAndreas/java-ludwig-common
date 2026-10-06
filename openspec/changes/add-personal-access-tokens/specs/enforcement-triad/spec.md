## ADDED Requirements

### Requirement: `RuleGroup.CREDENTIALS` owns the credential-handling structural rules

`architecture-rules` SHALL gain a `CREDENTIALS` rule group, enabled by default, carrying the
structural rules that make the PAT attenuation invariant mechanical rather than documented.

The group is named for the concern and not for the feature, so that a second credential format - a
deploy key, a signed webhook secret - lands in the same group rather than starting a third pattern.

#### Scenario: A module declares a second PAT store, SPI or token format
- **WHEN** any module other than `pat-core` and `pat-spring-boot-starter` declares a type that
  stores, mints or parses personal-access-token material
- **THEN** the `CREDENTIALS` group fails the build naming the type, for the same reason
  `RuleGroup.CACHING` fails a second cache SPI: two implementations of one mechanism is the defect,
  and the second one is always written by someone who did not find the first

#### Scenario: Raw secret material is read from an unpermitted package
- **WHEN** a class outside `pat-core`'s token package and the issuance service calls the raw-secret
  accessor on the secret carrier
- **THEN** the build fails naming the class

#### Scenario: The secret carrier is passed to a logger or an audit attribute
- **WHEN** the secret carrier type is passed as an argument to an SLF4J logging method or into an
  audit event attribute
- **THEN** the build fails. The rule matches on the **typed** parameter, which is what makes it
  checkable at all; a raw `String` that has already escaped the carrier defeats it, which is why the
  carrier's own `toString()` masks and why the reveal accessor is fenced as well - three partial
  mechanisms rather than one complete one, because no complete one exists

#### Scenario: A second code path constructs a PAT-credentialed principal
- **WHEN** any class other than the designated converter constructs a principal carrying the
  personal-access-token credential kind
- **THEN** the build fails. This is the rule that holds the attenuation invariant: the intersection
  cannot be bypassed if there is exactly one constructor of such a principal and it performs the
  intersection

### Requirement: A weak digest algorithm in the PAT hashing path is a Checkstyle failure

`checkstyle-rules` SHALL gain one `RegexpSinglelineJava` rule, carrying an `id`, that fails on a
weak digest algorithm name at a PAT hashing call site.

This belongs to Checkstyle and not to ArchUnit under the triad's existing split: the algorithm is a
string literal argument, and bytecode analysis cannot see a string constant's value. It is the same
reason `SecondRedactionMask` is a Checkstyle rule rather than an ArchUnit one.

#### Scenario: A weak algorithm name is introduced
- **WHEN** source in the PAT hashing path names a weak digest algorithm
- **THEN** `mvn validate` fails on that module, before the compiler runs

#### Scenario: The rule must be suppressed for a legitimate reason
- **WHEN** a suppression is needed
- **THEN** it uses the id form - `// SUPPRESS CHECKSTYLE ID <id> - reason` - because several checks
  share the `RegexpSinglelineJava` class name and naming the class switches all of them off at once

### Requirement: This change's unmechanisable rules are recorded, not dropped

The six conventions this change introduces that no build can check SHALL be recorded with their
reasons, and each SHALL carry a comment at the point of the rule.

The existing requirement "Where no mechanical check is possible, the reason is recorded in a comment"
states the general obligation. This requirement discharges it for this change explicitly, because a
list of unenforceable rules is the backlog for the next improvement and is more useful than the part
that already works.

#### Scenario: The unmechanisable set is enumerated
- **WHEN** this change's enforcement table is read
- **THEN** it names exactly six, each with its reason and its partial mitigation:
  1. **The edge must cache the exchange response.** The edge configuration is not in this repository.
     Mitigated by the response declaring its own lifetime, and by an issuer metric on exchanges per
     distinct PAT that makes a non-caching edge visible as a step change in traffic.
  2. **A plain digest is correct only because the secret is full-entropy and machine-generated.** No
     check can assert that the reasoning still holds if the generator changes. A comment at the
     hashing site states the dependency.
  3. **The secret is displayed exactly once.** The issuer returns it once and has no path to re-read
     it, but what the consuming UI does with it is outside any build here.
  4. **The checksum is not a security control.** There is nothing to detect, only a misreading to
     pre-empt, so it is a comment by construction.
  5. **The edge's client-facing `401` contract.** Same reason as 1. Mitigated by publishing the
     problem type and the management location as constants in `pat-core` and documenting the required
     response verbatim in the module README, so the edge configures against a definition rather than
     inventing one.
  6. **A long-lived connection re-derives authority on an interval.** Nothing in this repository holds
     a connection open, so there is no code for a rule to fire on - and a rule written now would pass
     vacuously and keep passing after the rule had been broken elsewhere, which is worse than no rule
     because it reads as coverage.

### Requirement: A check that cannot be written yet is deferred to a named change, not omitted

Where a convention is specified before the code it governs exists, the change SHALL record the
enforcing check as an obligation on the named future change that will introduce that code, rather
than writing a vacuous rule or leaving the gap unstated.

A rule that passes because there is nothing to check is the most expensive kind of absent rule: it
appears in the enforcement table, it stays green forever, and it gives the next reader a reason not
to look.

#### Scenario: The streaming revalidation check is located
- **WHEN** an agent or a reviewer asks what enforces the long-lived-connection revalidation
  requirement
- **THEN** the answer is that no check exists yet, that the configuration property and its ceiling
  check ship with this change so the knob exists before the transport does, and that the enforcing
  test is a recorded obligation on the MCP starter change - the first change that will have a
  transport to enforce it against

#### Scenario: A vacuous rule is proposed instead
- **WHEN** a change proposes an ArchUnit rule whose matching set is empty in every module
- **THEN** that is a finding, not coverage, and the deferral above is the expected form instead

#### Scenario: A reader looks for the reason at the code rather than in the spec
- **WHEN** any of the six sites is read in source
- **THEN** a comment at that site states why no check is possible, rather than the reason living only
  in this spec where the next person to change the code will not look
