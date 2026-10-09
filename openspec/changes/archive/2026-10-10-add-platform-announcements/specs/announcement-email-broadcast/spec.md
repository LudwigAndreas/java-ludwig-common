## Purpose

Sends an announcement by email as well as showing it, by paging the audience at publication time and
creating ordinary deliveries — so that one message to a hundred thousand mailboxes reuses the
dispatcher, the retry schedule, the rate limits and the suppression list that already exist, and can
be watched and cancelled while it runs.

## ADDED Requirements

### Requirement: The email audience is a snapshot and the inbox audience stays live

The set of people emailed for an announcement SHALL be resolved from the audience predicate at
publication time. The set of people who can see it SHALL continue to be resolved at read time.

The two SHALL be allowed to diverge, and that divergence SHALL NOT be treated as an error: an email
is physically sent at an instant and cannot be unsent, whereas visibility is a question asked again
on every read.

#### Scenario: Somebody gains the role after publication

- **WHEN** a recipient is granted the targeted role after an announcement with email was published
- **THEN** they can see the announcement and they are not emailed, because the send already happened

#### Scenario: Somebody loses the role after publication but before the fan-out reaches them

- **WHEN** a recipient held the targeted role at publication and loses it while the fan-out is still
  running
- **THEN** whether they are emailed is not guaranteed either way, and this is stated rather than
  implied: the snapshot is the audience at publication, and a run lasting minutes cannot also be
  instantaneous. They stop seeing the announcement immediately regardless

#### Scenario: A deployment needs the opposite guarantee

- **WHEN** a deployment requires that nobody who has lost the role is emailed
- **THEN** that is not provided by this change, and the honest answer is to target a narrower
  audience rather than to make the fan-out re-check membership per row, which would make a long run
  produce a different audience than the one that was approved

### Requirement: The fan-out is batched, resumable and exactly-once

The fan-out SHALL page the audience in bounded batches and SHALL NOT hold one transaction open for
the whole run, which would pin the oldest transaction id and stop autovacuum on the busiest tables in
the service.

It SHALL record its position so that a run interrupted by a lost lease, a restart or a cancellation
resumes rather than restarting, and SHALL create no second delivery for a recipient it has already
processed. Exactly-once SHALL rest on the existing uniquely-indexed per-delivery dedup key rather
than on a second mechanism.

#### Scenario: A run is interrupted and resumes

- **WHEN** a fan-out over a large audience is interrupted after some batches have committed, and
  resumes
- **THEN** it continues from its recorded position and the total number of deliveries created equals
  the audience size

#### Scenario: A resumed run overlaps work it already did

- **WHEN** a resumed run re-processes a recipient whose delivery was already created
- **THEN** no second delivery exists for that recipient and channel, and the run does not fail —
  the unique dedup key makes the overlap harmless rather than needing to be avoided

#### Scenario: The run holds no long transaction

- **WHEN** a fan-out over a hundred thousand recipients runs
- **THEN** it commits in bounded batches, and the longest transaction it opens is one batch

#### Scenario: Two replicas start the same fan-out

- **WHEN** more than one replica attempts the same announcement's fan-out
- **THEN** only one proceeds, under the platform's existing leased mutex, and no recipient receives
  two emails

### Requirement: The fan-out is a long-running operation and maps onto the platform envelope

The fan-out SHALL be reported through the platform's operation envelope, built with the platform's
builders, with its own run table rather than a shared one. Its status SHALL be expressed in the
platform's status vocabulary, and the service SHALL NOT declare a second enum restating those states.

A richer internal lifecycle is permitted and SHALL map onto the envelope, remaining visible in the
envelope's detail.

#### Scenario: A publish that sends email

- **WHEN** an announcement in a category including email is published
- **THEN** the response is `202`, names a status resource for the fan-out, and the announcement is
  already visible in the feed — the inbox half is complete before the email half has started

#### Scenario: A caller polls a running fan-out

- **WHEN** a caller polls the status resource while the fan-out is running
- **THEN** the envelope reports a running status with progress, and carries a retry hint

#### Scenario: A caller polls a finished fan-out

- **WHEN** the fan-out has finished
- **THEN** the envelope reports a terminal status, carries no retry hint, and links to the
  announcement

#### Scenario: A second status enum is declared

- **WHEN** an enum is declared for the fan-out that restates the platform's status values
- **THEN** the build fails

#### Scenario: A publish that sends no email

- **WHEN** an announcement in an inbox-only category is published
- **THEN** no fan-out run is created and the response names no status resource, because there is no
  operation to watch

### Requirement: The fan-out can be cancelled, and cancellation is cooperative

A fan-out SHALL be cancellable while it is running. The cancel endpoint SHALL answer `202`, because
the stop is requested rather than performed, and cancelling an already-terminal run SHALL return its
envelope rather than an error.

Cancellation SHALL stop the creation of further deliveries. It SHALL NOT attempt to recall
deliveries already created, and the envelope SHALL make the partial outcome visible rather than
reporting the run as though it had not sent anything.

#### Scenario: A run is cancelled part way

- **WHEN** a fan-out is cancelled after some batches have committed
- **THEN** the endpoint answers `202`, no further deliveries are created, and the envelope reports a
  cancelled status whose progress states how many were created before the stop

#### Scenario: Deliveries already created are not recalled

- **WHEN** a fan-out is cancelled
- **THEN** the deliveries already created continue to be dispatched, because they are already
  committed work and silently dropping them would make the count the envelope reported a lie

#### Scenario: An already-finished run is cancelled

- **WHEN** a cancel is requested for a run that has already finished
- **THEN** the envelope is returned rather than a conflict

### Requirement: Every per-recipient rule still applies to a broadcast email

A delivery created by the fan-out SHALL be an ordinary delivery and SHALL be settled by the existing
fan-out rules: the suppression list, the recipient's preferences for the category class, quiet hours,
and address resolution. A broadcast SHALL NOT be a way to bypass any of them.

#### Scenario: A recipient in the audience is on the suppression list

- **WHEN** the fan-out reaches a recipient whose address has a permanent suppression entry
- **THEN** their delivery is suppressed, as it would be for any other notification

#### Scenario: A recipient in the audience has no usable address

- **WHEN** the fan-out reaches a recipient with no verified address
- **THEN** their delivery is terminal with a reason, and the run continues — one unreachable
  recipient must not stop a broadcast

#### Scenario: A recipient is inside their quiet hours

- **WHEN** the fan-out reaches a recipient inside their quiet window and the category class is
  `PLATFORM`
- **THEN** their delivery is deferred to the end of the window rather than sent

#### Scenario: The announcement's category is declinable

- **WHEN** the category class is `MARKETING` and a recipient has opted out of that category
- **THEN** their delivery is suppressed

#### Scenario: The rate limit applies

- **WHEN** a channel rate limit is configured and a broadcast creates more deliveries than the
  window permits
- **THEN** the limit is honoured cluster-wide as for any other traffic, and the broadcast drains
  over several windows rather than exceeding it

### Requirement: A broadcast is observable without being an unbounded metric

The fan-out SHALL be measurable — how many are running, how many deliveries each has created, how
long one took — and SHALL NOT introduce a metric tag valued by recipient, announcement content or
delivery identifier.

Tagging by announcement identifier SHALL NOT be used, because the number of announcements grows
without bound over time.

#### Scenario: Broadcasts are measured

- **WHEN** fan-outs run
- **THEN** their count, their progress and their duration are observable, tagged by category and
  outcome only

#### Scenario: A tag valued by announcement is proposed

- **WHEN** a metric is tagged by announcement identifier
- **THEN** it is a finding: announcements accumulate, so the series count grows for the lifetime of
  the deployment
