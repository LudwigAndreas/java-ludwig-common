## ADDED Requirements

### Requirement: This change's structural rules are ArchUnit rules local to the service

The four shapes this change makes possible and forbids SHALL each be failed by an ArchUnit rule in
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

### Requirement: Two of this change's rules are enforced by the compiler, which is stronger than a rule

Where the type system can make a mistake unrepresentable, this change SHALL use it rather than add
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

### Requirement: Two of this change's rules are behavioural and are enforced by tests, not by the triad

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

### Requirement: This change's unmechanisable rules are recorded, not dropped

The four conventions this change introduces that no build can check SHALL be recorded with their
reasons, and each SHALL carry a comment at the point of the rule.

#### Scenario: The unmechanisable set is enumerated

- **WHEN** this change's enforcement table is read
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

### Requirement: No rule is written for the push transport this change deliberately does not add

Real-time push is a stated non-goal. This change SHALL NOT add a rule forbidding or governing
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
