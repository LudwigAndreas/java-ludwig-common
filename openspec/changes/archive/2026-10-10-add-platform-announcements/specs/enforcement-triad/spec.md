## ADDED Requirements

### Requirement: This change's structural rules are ArchUnit rules local to the service

The shapes this change forbids SHALL each be failed by an ArchUnit rule in `notification-service`'s
own architecture test sources, alongside the in-app carve-out rules, and not by a new `RuleGroup` in
`architecture-rules`.

The placement argument is the one already established for the in-app rules: a rule about an
announcement aggregate, an audience predicate and a notification category vocabulary can only ever
fire in one module, and in every other service inheriting the shared jar it would pass with nothing
to check. A rule that passes vacuously is worse than an absent one.

#### Scenario: A materialized audience is declared on the announcement

- **WHEN** a collection-valued field holding subjects, or a mapped association to a per-subject
  audience row, is declared on the announcement entity
- **THEN** the service's architecture test fails, because one row per announcement regardless of
  audience size is the property the whole aggregate exists for

#### Scenario: A second status enum is declared for the fan-out

- **WHEN** an enum in this service restates the platform's operation status values
- **THEN** the build fails. This one is **not** a new rule: `architecture-rules`'
  `RuleGroup.OPERATIONS` already forbids it platform-wide, and this change relies on it rather than
  adding a copy

#### Scenario: The inbox query gains an announcement term

- **WHEN** a class in the inbox read path depends on the announcement entity or its repository
- **THEN** the service's architecture test fails. The inbox's correctness rests on a single owner
  predicate, and the two feeds are separate precisely so that a derived visibility term never lands
  on the service's most-polled endpoint

#### Scenario: The audience fields are made filterable

- **WHEN** the announcement's audience kind or value carries the filterable annotation
- **THEN** the service's architecture test fails, because filtering by audience lets a caller
  enumerate which roles have been addressed

### Requirement: The targetable-role allowlist is enforced in one place and the bypass is checked

Resolution of a requested role target against the configured allowlist SHALL happen in exactly one
method, and no other class SHALL construct a role-targeted audience without passing through it. A
second construction path SHALL fail the build.

This is an ArchUnit rule rather than a comment because the failure is silent in the direction that
matters: a second path that skipped the allowlist would work perfectly for every valid role and
would quietly also accept every invalid one.

#### Scenario: A second path constructs a role audience

- **WHEN** a class outside the audience-resolution package constructs a role-targeted audience
  directly
- **THEN** the service's architecture test fails

#### Scenario: The allowlist is consulted

- **WHEN** a publish names a role
- **THEN** it passes through the single resolution method, which is the only place the allowlist is
  read

### Requirement: The category catalogue is validated at startup, not at publish

A catalogue entry naming an unknown channel or an unknown category class SHALL fail the application's
startup, in the same shape as the existing configuration relation checks, rather than failing the
first publish that uses it.

A typo in a catalogue is otherwise discovered by an announcer at the moment they are trying to
announce something, which is the worst possible time to learn about it.

#### Scenario: A catalogue names an unknown channel

- **WHEN** a category declares a channel that is not a known transport
- **THEN** the application refuses to start, naming the category and the value

#### Scenario: A catalogue names an unknown class

- **WHEN** a category declares a category class that does not exist
- **THEN** the application refuses to start

#### Scenario: A valid catalogue

- **WHEN** every category names known channels and a known class
- **THEN** the application starts and the startup line reports how many categories were loaded

### Requirement: The split bypass is enforced by exhaustiveness, not by a rule

Adding the third category class SHALL make the existing bypass decision exhaustive over the class
vocabulary, so that a fourth class added later does not compile until somebody has decided whether it
bypasses opt-out and whether it bypasses quiet hours.

A `default` branch over the category class in the preference path SHALL NOT be introduced, because it
would silently answer for the next class added.

#### Scenario: A fourth category class is added

- **WHEN** a constant is added to the category class vocabulary
- **THEN** compilation fails until the bypass decision is stated for it

#### Scenario: A default branch is introduced

- **WHEN** a `default` arm is added over the category class in the preference path
- **THEN** it is a finding: the exhaustiveness is the check, and this is the same mistake the in-app
  change explicitly avoided in three pre-existing switches

### Requirement: This change's unmechanisable rules are recorded, not dropped

The conventions this change introduces that no build can check SHALL be recorded with their reasons,
and each SHALL carry a comment at the point of the rule.

#### Scenario: The unmechanisable set is enumerated

- **WHEN** this change's enforcement table is read
- **THEN** it names exactly five, each with its reason and its mitigation:
  1. **A configured category's class being the right product answer.** Nothing can detect a category
     declared `PLATFORM` that should have been `MARKETING` — it is a statement about what the
     organisation may impose on people, exactly the shape of a cache's `CachePurpose`. Mitigated by
     the catalogue being configuration owned by whoever owns the notification catalogue rather than
     by the announcing service, and by the three-way table being stated at the point of the enum.
  2. **The targetable-role allowlist being the right list.** A build can check that it is consulted,
     and does; it cannot check that a role on it should be addressable.
  3. **An announcement's audience being the one that was approved.** `EVERYONE` and
     `ROLE(ADMIN)` are both valid and only one of them is usually correct. Mitigated by publishing
     being audited through the single `AuditSink`, so the decision has a named author.
  4. **The window being an appropriate length.** The maximum is capped, which bounds the mistake but
     does not detect it.
  5. **The divergence between the emailed snapshot and live visibility being acceptable.** It is
     specified and tested, but whether a deployment can live with it is a judgement about their
     tolerance for emailing somebody who has since lost a role. Mitigated by being stated in the
     README rather than only in a spec.

### Requirement: No rule is written for the audience kinds this change does not add

Organisation-unit audiences and notification-local groups are stated non-goals. This change SHALL NOT
add a rule forbidding or governing either, because there is no code for such a rule to fire on.

The obligation belongs to the change that projects organisation units, which must then establish that
the audience vocabulary is extended rather than special-cased.

#### Scenario: A vacuous rule is proposed

- **WHEN** a rule is added asserting that no class resolves an organisation unit
- **THEN** it is a finding: it would pass today and keep passing after a later change had made the
  audience vocabulary inconsistent
