## Purpose

Lets a notification reach a recipient inside the product rather than by pushing a message at them,
so that somebody who has declined every push channel, or who has no verified contact address, is
still reachable — and so the service distinguishes a transport that interrupts a person from one
that waits to be read.

## ADDED Requirements

### Requirement: `IN_APP` is a requestable transport

The service SHALL accept `IN_APP` wherever it accepts a channel: in a request's `channels`, in a
stored delivery, and in a read-back response. The three representations of the channel vocabulary
SHALL stay in step, and a representation missing a transport the others have SHALL fail the build
rather than a request.

#### Scenario: A caller requests the in-app channel

- **WHEN** a request names `IN_APP` in `channels` for a recipient identified by user id
- **THEN** the response is `202 Accepted` and reports one delivery whose channel is `IN_APP`

#### Scenario: A caller mixes in-app with a push channel

- **WHEN** a request names both `EMAIL` and `IN_APP` for one recipient
- **THEN** two deliveries are reported, one per channel, each settled independently — the email
  may be suppressed while the in-app delivery is delivered, and neither outcome affects the other

#### Scenario: One representation of the vocabulary is missing a transport

- **WHEN** a transport constant exists in the persistence vocabulary but not in the wire or
  service-layer vocabulary
- **THEN** the build fails, rather than a request failing at runtime on an unmappable constant

### Requirement: An `IN_APP` delivery is settled in the transaction that accepts the request

An `IN_APP` delivery SHALL reach a terminal state in the fan-out transaction, alongside the
existing in-transaction settlements. It SHALL NOT be enqueued, claimed, leased, retried or backed
off, and SHALL NOT be visible to the dispatch poller in any state.

The service SHALL have no transport implementation registered for `IN_APP`. Registering one SHALL
fail the build, because a transport implementation is contractually forbidden from touching the
database and this destination is the database.

#### Scenario: An in-app notification is accepted

- **WHEN** a request naming `IN_APP` is accepted
- **THEN** the delivery is already terminal in the `202` response body, and an inbox item for the
  recipient exists as soon as the accepting transaction commits

#### Scenario: The accepting transaction rolls back

- **WHEN** the fan-out transaction fails after an `IN_APP` delivery has been settled
- **THEN** neither the delivery nor the inbox item exists — the item cannot outlive a request that
  was never accepted

#### Scenario: The dispatch poller runs with in-app deliveries present

- **WHEN** the poller claims due deliveries
- **THEN** no `IN_APP` delivery is ever claimed, and the claim query returns the same rows it
  would have returned had the in-app deliveries not existed

#### Scenario: A transport implementation claims to support in-app

- **WHEN** a transport implementation reports that it supports `IN_APP`
- **THEN** the build fails

#### Scenario: Status history records the settlement

- **WHEN** an `IN_APP` delivery is settled
- **THEN** the status trail shows it created and then settled, in the same way a suppressed or
  batched delivery's trail does, so an operator reading the trail can tell why it never queued

### Requirement: A channel is classified as interrupting or passive, once

Each transport SHALL carry exactly one classification — interrupting, meaning delivery reaches a
person who is not asking for it, or passive, meaning delivery stores something the recipient reads
when they choose. `IN_APP` SHALL be the only passive transport introduced here; `EMAIL`, `CHAT` and
`WEBHOOK` SHALL be interrupting.

The classification SHALL be declared in one place and consulted by every rule whose justification
is that delivery interrupts somebody. A rule that special-cases a named transport instead of
consulting the classification SHALL fail the build.

#### Scenario: A new transport is added without a classification

- **WHEN** a transport constant is added and no classification is declared for it
- **THEN** the build fails, rather than the transport silently inheriting one of the two behaviours

#### Scenario: A preference rule names a transport constant directly

- **WHEN** a dispatch-eligibility rule branches on the `IN_APP` constant rather than on the
  classification
- **THEN** the build fails

### Requirement: Quiet hours, digest and address suppression do not apply to a passive channel

For a passive channel the service SHALL NOT defer a delivery into a quiet window, SHALL NOT fold
it into a digest, and SHALL NOT consult the address suppression list. Each has a reason specific to
interruption: a quiet window exists so nobody is woken, and an item that waits to be read wakes
nobody; a digest exists to reduce a count of interruptions; and a suppression entry is a fact about
a delivery address, of which a passive channel has none.

#### Scenario: An in-app notification arrives during the recipient's quiet hours

- **WHEN** a non-transactional request naming `IN_APP` is accepted while the recipient is inside
  their configured quiet window
- **THEN** the delivery is delivered immediately and the inbox item is readable at once, rather
  than deferred to the next opening or suppressed

#### Scenario: The recipient's address is on the suppression list

- **WHEN** a request names `IN_APP` for a recipient whose email address carries a permanent
  suppression entry
- **THEN** the in-app delivery is delivered, because the suppression entry is about the address and
  no address is used

#### Scenario: A digest window is configured for the category

- **WHEN** digest is enabled for a category and a request names both `EMAIL` and `IN_APP`
- **THEN** the email delivery is batched for the digest and the in-app delivery is delivered
  immediately

### Requirement: Per-category opt-out applies to a passive channel

A recipient SHALL be able to decline a declinable category on `IN_APP`, by the exact
`(category, channel)` pair or by the blanket opt-out for the channel, with the same precedence the
service already applies — the specific answer wins whichever way it points. An undeclinable
category SHALL bypass it, as on every other channel.

#### Scenario: The recipient has opted out of the category on in-app

- **WHEN** a declinable category is requested on `IN_APP` for a recipient who has opted out of
  that exact pair
- **THEN** the delivery is suppressed for opt-out and no inbox item is created

#### Scenario: The recipient has declined everything except one category

- **WHEN** a recipient holds a blanket opt-out for `IN_APP` and an explicit opt-in for one category
- **THEN** that category is delivered to the inbox and every other declinable category is suppressed

#### Scenario: An undeclinable category is requested

- **WHEN** a category the platform owner has classed as transactional is requested on `IN_APP` for
  a recipient holding a blanket in-app opt-out
- **THEN** the delivery is delivered, because an undeclinable category bypasses preferences on
  every channel

### Requirement: A configured fallback delivers to the inbox when every interrupting channel is suppressed

A deployment SHALL be able to declare, per category class, that the inbox is the fallback
destination. Where it is declared and **every** interrupting delivery for one recipient in one
fan-out settles as suppressed, the service SHALL produce an additional `IN_APP` delivery for that
recipient, settled in the same transaction, so a notification the platform owner has declared
undeclinable lands somewhere.

The fallback SHALL be configuration owned by whoever owns the notification catalogue, and SHALL NOT
be expressible as a field on a request — from inside any one calling service its own notification
always looks important, so a per-request flag would be set by everybody.

#### Scenario: Every push channel is suppressed and a fallback is configured

- **WHEN** a request names `EMAIL` and `CHAT` for a recipient who has opted out of both, and the
  category class declares the inbox as its fallback
- **THEN** a third delivery on `IN_APP` is reported, delivered, and an inbox item exists

#### Scenario: Some push channel survives

- **WHEN** a request names `EMAIL` and `CHAT`, the recipient has opted out of chat only, and a
  fallback is configured
- **THEN** no fallback delivery is created, because the notification already has a destination

#### Scenario: The recipient has also opted out of the fallback channel

- **WHEN** every interrupting delivery is suppressed and the recipient additionally holds an
  in-app opt-out for that declinable category
- **THEN** no fallback delivery is created — the fallback restores a destination the recipient has
  not declined, and SHALL NOT override a stated preference

#### Scenario: No fallback is configured

- **WHEN** every interrupting delivery for a recipient is suppressed and no fallback is declared
  for the category class
- **THEN** behaviour is exactly as it is today: the deliveries are suppressed and nothing else is
  created

#### Scenario: The request already asked for in-app

- **WHEN** a request names `EMAIL` and `IN_APP`, the email is suppressed, and a fallback is
  configured
- **THEN** exactly one `IN_APP` delivery exists — the fallback never duplicates a channel the
  caller already requested

### Requirement: An in-app delivery requires a recipient with an identity

An `IN_APP` delivery SHALL require a recipient the service can attribute an inbox to. A recipient
given only as a literal address SHALL produce a terminal `IN_APP` delivery, decided at fan-out,
with a failure reason stating that the channel needs an identified recipient.

This SHALL be a terminal delivery rather than a rejected request, so a request naming several
recipients where one is a literal address still delivers to the others.

#### Scenario: A literal address recipient is requested on in-app

- **WHEN** a request names `IN_APP` for a recipient expressed as a bare address
- **THEN** that delivery is terminal with a reason naming the unresolvable recipient, and the
  request is still accepted with `202`

#### Scenario: A mixed-recipient request

- **WHEN** one request names `IN_APP` for two recipients, one by user id and one by literal address
- **THEN** one inbox item is created and one delivery is terminal, and the request is accepted

#### Scenario: The recipient is unknown to the identity projection

- **WHEN** a request names `IN_APP` for a user id that has not yet arrived in the local identity
  projection
- **THEN** the inbox item is created and addressed to that user id, because the projection is fed
  asynchronously and a new user can be notifiable before their record lands — consistent with the
  service already not treating an unknown user as a failure

### Requirement: The in-app channel carries no address and nothing addressable is logged

An `IN_APP` delivery SHALL store no recipient address, SHALL contribute no recipient, body or
identifier to a metric tag, and SHALL carry no address or content on the lifecycle event topic.
Where an in-app body is logged at all it SHALL be reduced to its length, under the same masking the
service already applies to a rendered body.

#### Scenario: An in-app delivery is persisted

- **WHEN** an `IN_APP` delivery row is written
- **THEN** its recipient address is absent, and no query option can filter deliveries by it

#### Scenario: In-app traffic is measured

- **WHEN** in-app deliveries are counted
- **THEN** the metric is tagged by channel, category and outcome only, with no tag valued by
  recipient or item
