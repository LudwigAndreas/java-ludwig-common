# The enforcement triad

## Purpose
Three tools check this codebase, and they must not overlap. Two tools checking one rule means two
places to change it, two error messages for one mistake, and eventually two rules that disagree.

## Requirements

### Requirement: Each class of check has exactly one owner
A check SHALL belong to exactly one of three tools:

| Tool | Owns |
|---|---|
| `architecture-rules` (ArchUnit) | structure and dependencies |
| `checkstyle-rules` | source text |
| SonarQube | bugs and security |

#### Scenario: A new rule is about a type's name, package or dependencies
- **WHEN** a change needs to forbid a shape that is visible in bytecode — a second audit SPI, a
  module-local status enum, a dependency in the wrong direction
- **THEN** the rule goes in `architecture-rules` as an ArchUnit rule in the appropriate
  `RuleGroup`, and not in Checkstyle

#### Scenario: A new rule is about the text of the source
- **WHEN** a change needs to forbid something that is a fact about source text rather than about
  structure
- **THEN** the rule goes in `checkstyle-rules`, and not in ArchUnit

#### Scenario: The rule is about a constant's value
- **WHEN** the rule concerns the *value* of a string constant, as the second-redaction-mask rule
  does
- **THEN** it must be a Checkstyle rule, because ArchUnit reads bytecode and `JavaField` does not
  expose a constant's value. The split between `SecondRedactionMask` (Checkstyle) and the
  companion audit-SPI rule (ArchUnit) is deliberate for exactly this reason

### Requirement: Where no mechanical check is possible, the reason is recorded in a comment
A rule that bytecode analysis and source-text analysis both cannot express SHALL be recorded as a
comment at the point of the rule, saying why. A rule with neither a check nor such a note is a
rule that will be broken silently.

#### Scenario: A convention cannot be mechanised
- **WHEN** a change introduces a convention no tool in the triad can check — for example that a
  cache's declared `CachePurpose` matches what the TTL actually means
- **THEN** the reason is written at the point of the rule, which is already this repository's
  convention for checks bytecode analysis cannot express

### Requirement: Checkstyle runs on every module at validate
Checkstyle SHALL be bound to the `validate` phase on every module, before the compiler, reading
its configuration from `checkstyle-rules`' source tree for in-reactor builds so a clean clone
builds without installing it first.

#### Scenario: A clean clone is built
- **WHEN** `mvn clean install` is run on a fresh clone with nothing installed locally
- **THEN** Checkstyle resolves its configuration from the in-reactor module and the build
  proceeds, rather than failing on a missing artifact

### Requirement: Formatting mirrors IntelliJ IDEA defaults exactly
The formatting rules SHALL mirror IntelliJ IDEA's out-of-the-box defaults one for one. There is
no IDE code style to import, and Reformat Code / Optimize Imports always produce a build-clean
file.

#### Scenario: A change proposes a custom formatting scheme
- **WHEN** a change would introduce a formatting rule that diverges from IntelliJ's defaults
- **THEN** it is refused: every contributor's IDE would then produce files the build rejects, and
  the cost lands on every commit rather than on the one that introduced it

### Requirement: A suppression names its rule and gives a reason
Every Checkstyle suppression SHALL name the rule and state why. For any rule the configuration
gives an `id`, the id form SHALL be used.

#### Scenario: A rule that shares the RegexpSinglelineJava class name is suppressed
- **WHEN** a suppression is needed for `NonAsciiSourceText`, `ConsoleOutput`,
  `SecondRedactionMask` or any other rule the configuration gives an `id`
- **THEN** the `// SUPPRESS CHECKSTYLE ID <id> - reason` form is used, because several checks
  share the `RegexpSinglelineJava` class name and naming the class switches all of them off at
  once

### Requirement: The in-app channel's structural rules are ArchUnit rules local to the service

The four shapes the in-app channel makes possible and forbids SHALL each be failed by an ArchUnit rule in
`notification-service`'s own architecture test sources, not by a new `RuleGroup` in
`architecture-rules`.

The placement is deliberate. `architecture-rules` is a shared, test-scoped jar: a rule group added
there is a rule every consuming service inherits, and these four rules are about a channel
vocabulary, a delivery entity and a preference evaluator that exist in exactly one module. A rule
that can only ever fire in one module belongs in that module, which is already the precedent set by
the two `SqlConfinementTest` carve-outs.

#### Scenario: A read-state field is declared on a delivery entity

- **WHEN** a field recording that a recipient has seen, read or dismissed something is declared on
  a class assignable to the delivery entity
- **THEN** the service's architecture test fails, because a field name is visible in bytecode

#### Scenario: The in-app settlement reaches the delivery content table

- **WHEN** a class on the in-app settlement path accesses the delivery content entity or its
  repository
- **THEN** the service's architecture test fails, which is what keeps an unread body out of the
  seven-day purge without anybody having to remember the window

#### Scenario: A dispatch-eligibility rule branches on the transport constant

- **WHEN** a class in the preference-evaluation path reads the `IN_APP` enum constant instead of
  consulting the interrupting/passive classification
- **THEN** the service's architecture test fails, because an enum constant field access is visible
  in bytecode. This is the rule that stops the classification decaying into the six special cases
  it exists to replace

#### Scenario: The inbox owner field is made filterable

- **WHEN** the inbox item's owner field carries the filterable annotation
- **THEN** the service's architecture test fails, because an annotation's presence is visible in
  bytecode — and a filterable owner is an existence oracle for other people's notifications

#### Scenario: A rule group is proposed in the shared jar instead

- **WHEN** one of these four rules is added to `architecture-rules` as a shared `RuleGroup`
- **THEN** it is a finding: every other service would inherit a rule about a vocabulary it does
  not have, and the rule would pass vacuously there

### Requirement: Two of the in-app channel's rules are enforced by the compiler, which is stronger than a rule

Where the type system can make a mistake unrepresentable, the in-app channel SHALL use it rather than add
a check that fires after the fact.

- The interrupting/passive classification SHALL be a mandatory constructor argument of the channel
  enum, so a transport constant added without one does not compile.
- The three representations of the channel vocabulary SHALL continue to be bridged by the existing
  compile-time mapper, so a transport present in one and absent from another fails the build. This
  change adds no new mechanism for it; it relies on the one already in place and must not weaken it
  with a hand-written fallback.

#### Scenario: A transport is added without a classification

- **WHEN** a constant is added to the channel enum with no classification argument
- **THEN** compilation fails

#### Scenario: A mapper fallback is added to tolerate an unmapped transport

- **WHEN** a hand-written default or `unmappedTargetPolicy` relaxation is introduced that lets an
  unmapped transport constant pass
- **THEN** it is a finding: it converts a build failure into a runtime failure, which is the
  opposite of what the existing mechanism buys

### Requirement: Two of the in-app channel's rules are behavioural and are enforced by tests, not by the triad

Two rules concern what a method returns for a given argument rather than the structure or text of
the code, which neither ArchUnit nor Checkstyle can see. They SHALL be enforced by named tests, and
the reason the triad cannot own them SHALL be recorded at the point of the rule.

- **No transport implementation supports `IN_APP`.** A context test SHALL assert that the channel
  registry resolves nothing for `IN_APP`. ArchUnit cannot express it because support is reported by
  a predicate's return value, not by a type's shape.
- **The dispatch poller never claims an `IN_APP` delivery.** An integration test SHALL assert that
  a claim run with in-app deliveries present returns exactly the rows it would have returned
  without them.

#### Scenario: Somebody adds an in-app transport implementation

- **WHEN** a transport bean is registered that reports support for `IN_APP`
- **THEN** the context test fails at `verify`, naming the offending bean

#### Scenario: An in-app delivery is left in a claimable state

- **WHEN** an in-app delivery is written in a state the claim query would select
- **THEN** the integration test fails. The test must be named so failsafe runs it; a check of this
  kind named as a unit test never runs at all and reads as coverage

### Requirement: The in-app channel's unmechanisable rules are recorded, not dropped

The four conventions the in-app channel introduces that no build can check SHALL be recorded with their
reasons, and each SHALL carry a comment at the point of the rule.

#### Scenario: The unmechanisable set is enumerated

- **WHEN** the in-app channel's enforcement table is read
- **THEN** it names exactly four, each with its reason and its partial mitigation:
  1. **A transport's declared classification being the truth.** Nothing can detect a transport that
     declares itself passive while actually pushing at a person — the classification is a statement
     about the world, not about the code, exactly as `CachePurpose` is. A comment on the enum states
     the test to apply: does delivery reach somebody who is not asking for it right now?
  2. **The fallback being the right product decision.** That a category class should fall back to the
     inbox is a judgement by whoever owns the notification catalogue. A build can check that it is
     configuration rather than a request field — and that much *is* checked, by there being no
     request field to set — but not that the configured answer is correct.
  3. **A configured unread ceiling being a deliberate decision.** Purging a notification nobody has
     read discards information the recipient was meant to receive. Nothing can distinguish a
     considered ceiling from a copied one. Mitigated by the ceiling being a separate property from
     the ordinary window, so it cannot be set by accident, and by its purge being reported distinctly
     so an operator can see that unread items were discarded.
  4. **A client polling the unread count rather than paging the inbox.** The client is not in this
     repository. Mitigated by the count being its own operation that returns no content, and by its
     being observable separately from a list query, so a client doing it wrong is visible as traffic
     rather than invisible.

### Requirement: No rule is written for the push transport the in-app channel deliberately does not add

Real-time push is a stated non-goal. The in-app channel SHALL NOT add a rule forbidding or governing
server-sent events, websockets or a push provider, because there is no such code for the rule to
fire on.

A rule that passes because there is nothing to check is the most expensive kind of absent rule: it
appears in the enforcement table, stays green forever, and gives the next reader a reason not to
look. The obligation belongs to the change that introduces push, which must then establish that
push is decoration over a durable inbox and never the delivery mechanism.

#### Scenario: A vacuous push rule is proposed

- **WHEN** a rule is added asserting that no class in the service opens a streaming response
- **THEN** it is a finding: it would pass today, pass tomorrow, and keep passing after a later
  change had made the inbox depend on a stream

#### Scenario: Push is later added

- **WHEN** a subsequent change introduces a push transport
- **THEN** that change owns the rule that an inbox item is durable before any push is attempted,
  and records it here

### Requirement: The announcement feature's structural rules are ArchUnit rules local to the service

The shapes the announcement feature forbids SHALL each be failed by an ArchUnit rule in `notification-service`'s
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
  `RuleGroup.OPERATIONS` already forbids it platform-wide, and the announcement feature relies on it rather than
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

### Requirement: The announcement feature's unmechanisable rules are recorded, not dropped

The conventions the announcement feature introduces that no build can check SHALL be recorded with their reasons,
and each SHALL carry a comment at the point of the rule.

#### Scenario: The unmechanisable set is enumerated

- **WHEN** the announcement feature's enforcement table is read
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

### Requirement: No rule is written for the audience kinds the announcement feature does not add

Organisation-unit audiences and notification-local groups are stated non-goals. The announcement feature SHALL NOT
add a rule forbidding or governing either, because there is no code for such a rule to fire on.

The obligation belongs to the change that projects organisation units, which must then establish that
the audience vocabulary is extended rather than special-cased.

#### Scenario: A vacuous rule is proposed

- **WHEN** a rule is added asserting that no class resolves an organisation unit
- **THEN** it is a finding: it would pass today and keep passing after a later change had made the
  audience vocabulary inconsistent
