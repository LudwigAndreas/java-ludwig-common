# notification-service

***English** · [Русский](README.ru.md)*

The platform's notification service: every other service asks this one to notify somebody instead of
talking to a mail server itself.

Two ingresses converge on one application service — a REST API, and a Kafka consumer for
fire-and-forget traffic where a broker exists. **This deployment has no broker, so REST is the
ingress**: one endpoint for a single notification, one for a batch, one to read a submitted request
back. Both ingresses call the same application service and neither decides anything the other does
not; turning the consumer on later is a flag, not a change. See
[Running without a broker](#running-without-a-broker).

A request fans out into one delivery per recipient per channel, held in a Postgres work queue; a
poller claims due deliveries with `FOR UPDATE SKIP LOCKED`, renders a FreeMarker template in the
recipient's language, and hands the result to a channel — SMTP, the internal chat API, or a signed
webhook.

One channel does not work that way. `IN_APP` stores the notification in the recipient's own inbox and
is settled in the transaction that accepted the request, never queued, because its destination is
this service's database rather than somebody else's network. It is the destination that still works
when a recipient has declined every push channel or has no verified address — see
[the inbox](#the-inbox-the-one-passive-channel).

It is built entirely from this repository's own modules. It used to add the two things none of them
provided - consumer-side idempotency and a leased distributed lock - and both have since been promoted
into the platform: the store into `idempotency-spring-boot-starter`, the lock into `job-core`. See
[Promoted: both platform gaps are filled](#promoted-both-platform-gaps-are-filled).

```console
$ curl -X POST https://notifications.internal/api/v1/notifications \
    -H 'Idempotency-Key: pwreset-4711' \
    -H 'Content-Type: application/json' \
    -d '{
          "templateKey": "password-reset",
          "category": "security",
          "categoryClass": "TRANSACTIONAL",
          "priority": "HIGH",
          "channels": ["EMAIL"],
          "recipients": [{"userId": "8f2c…"}],
          "variables": {"resetLink": "https://…", "expiresInMinutes": 15}
        }'

HTTP/1.1 202 Accepted
Location: /api/v1/notifications/0f8a…
{"id":"0f8a…","state":"FANNED_OUT","duplicate":false,
 "deliveries":[{"id":"3b1c…","channel":"EMAIL","recipientReference":"user:8f2c…","status":"PENDING"}]}
```

---

## Contents

- [The two aggregates, and why](#the-two-aggregates-and-why)
- [The delivery state machine](#the-delivery-state-machine)
- [The delivery queue is not the outbox](#the-delivery-queue-is-not-the-outbox)
- [Promoted: both platform gaps are filled](#promoted-both-platform-gaps-are-filled)
- [The request contract](#the-request-contract)
- [Running without a broker](#running-without-a-broker)
- [The Kafka topic](#the-kafka-topic)
- [How to author a template](#how-to-author-a-template)
- [How to add a channel](#how-to-add-a-channel)
- [The inbox: the one passive channel](#the-inbox-the-one-passive-channel)
- [Platform announcements](#platform-announcements)
- [Preferences, quiet hours and suppression](#preferences-quiet-hours-and-suppression)
- [Where preferences come from](#where-preferences-come-from)
- [Priority lanes and rate limits](#priority-lanes-and-rate-limits)
- [Behaviour at three replicas](#behaviour-at-three-replicas)
- [PII discipline and the retention policy](#pii-discipline-and-the-retention-policy)
- [Metrics, and the two that matter](#metrics-and-the-two-that-matter)
- [Operational runbook](#operational-runbook)
- [The published API document](#the-published-api-document)
- [Configuration](#configuration)
- [Deviations and known gaps](#deviations-and-known-gaps)

---

## The two aggregates, and why

This is the single most important thing in the design, and everything else follows from it.

| | `notification_request` | `notification_delivery` |
|---|---|---|
| means | what a caller asked for | one recipient × one channel |
| holds | template key, variables, category, priority, the recipients as asked for | **all** retry, attempt, lease, error and status state |
| base class | `AuditedEntity` — caller-owned, needs `createdBy` and a version | `GeneratedEntity` + explicit `@CreatedDate`/`@Version` |

A request to three recipients where one mailbox bounces has **no single status**. If retry state lived
on the request, that situation would be unrepresentable: you would have to pick one answer for all
three, and whichever you picked would be wrong for the others. Putting attempts, `lastError`,
`nextAttemptAt` and the lease on the delivery is what makes partial failure a first-class outcome
instead of a data-modelling accident.

The delivery deliberately does **not** extend `AuditedEntity`. Its claim step is a native `UPDATE`
that Hibernate never sees, so `@LastModifiedDate` would not fire for it and `updated_at` would report
the last *ORM* write rather than the last change — a timestamp that is wrong exactly when an operator
is trying to work out whether the queue is moving. The lifecycle instants are explicit columns and
each one means one thing.

## The delivery state machine

```
                    ┌─────────────────────────────────────────────┐
                    │              fan-out, one transaction        │
request: ACCEPTED ──┤  resolve recipient → preferences → quiet     │
         │          │  hours → suppression list                    │
         ├─ FANNED_OUT  └──────────────────────────────────────────┘
         └─ REJECTED (no delivery row could be created at all)


delivery:  ACCEPTED ─┬─────────────────────────────────→ PENDING ──→ CLAIMED ──→ SENT ──→ DELIVERED
                     │                                      ▲           │                (on receipt)
                     ├──→ SUPPRESSED  (opt-out, list, quiet)│           ├──→ FAILED ──┐
                     ├──→ BATCHED ──→ COLLAPSED  (digest)   │           │             │ backoff
                     ├──→ DEAD       (unresolvable)         │           └──→ DEAD     │ + jitter
                     │                                      └─────────────────────────┘
                     └──→ DELIVERED  (IN_APP: settled here, never queued)
           PENDING ──→ CANCELLED   (operator)
           DEAD    ──→ PENDING     (operator retry; attempts reset)
```

**Why `ACCEPTED` is a real state.** Every delivery is created `ACCEPTED` and then settled — in the
same transaction — into whichever state it belongs in. Nobody ever observes one sitting there, and
that is fine: the value is in the status trail, where *"created, then suppressed because the recipient
had opted out"* is a different and far more useful record than a row that simply appeared as
suppressed.

**`BATCHED` and `COLLAPSED` are additions** to the lifecycle as originally specified. Without a state
meaning "real, but not eligible for dispatch", a notification waiting to be folded into a digest is
indistinguishable from one the poller has not reached yet; and without a terminal state meaning
"folded into a digest that was sent", the members of a collapsed group would have to be recorded as
`CANCELLED`, which is a lie about what happened.

Every transition is appended to `notification_delivery_status_history`, mirroring the outbox module's
`OutboxStatusHistory`. Current status answers none of the questions an operator actually asks — how
long did it wait, how often did it fail before it died, was it suppressed before or after the
recipient unsubscribed. The trail answers all three.

## The delivery queue is not the outbox

`outbox-spring-boot-starter` is a dependency of this service and is used for **exactly one thing**:
publishing `NotificationDelivered` / `NotificationFailed` / `NotificationSuppressed` so other services
can react. That is what an outbox is for — making an event's publication atomic with a local state
change.

It is emphatically **not** the delivery queue, and the modules superficially fit well enough that this
is worth stating plainly. An outbox row has no notion of a channel, a priority lane, a schedule, a
per-provider rate limit, a suppression outcome, or a retry classification that distinguishes *"the
mailbox is full"* from *"the address does not exist"*. Reusing it would have looked like reuse and
produced a queue that cannot express most of what this service does.

What **is** reused is the outbox's *mechanics*, because they are correct and hard to get right twice:

| Outbox mechanic | Where it reappears |
|---|---|
| single native `UPDATE … WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED) RETURNING *` | `NotificationDeliveryRepository#claimBatch` |
| `locked_at` / `locked_by` lease columns | `claimed_at` / `claimed_by` |
| stale-reclaim sweeper | `StaleLeaseReclaimScheduler` |
| exponential backoff | `DeliveryBackoffCalculator` — **plus jitter**, which the outbox does not have |
| `SmartLifecycle` poller on an injected `TaskScheduler` | `DeliveryPollerScheduler` |
| append-only status history | `DeliveryStatusHistoryEntity` |

The jitter is the one genuine addition. An SMTP relay going down fails every claimed delivery within a
second or two of the others; without jitter they all get the same `next_attempt_at`, and the
recovering relay is hit by the entire backlog in one synchronised burst that fails them all again, in
lockstep, forever.

### The claim query, and the index that serves it

```sql
UPDATE notification_delivery
SET status = 'CLAIMED', claimed_at = :now, claimed_by = :owner, version = version + 1
WHERE id IN (
    SELECT d.id FROM notification_delivery d
    WHERE d.status IN ('PENDING', 'FAILED')
      AND d.channel = :channel
      AND d.priority >= :minPriorityWeight
      AND d.next_attempt_at <= :now
    ORDER BY d.priority DESC, d.next_attempt_at, d.id
    LIMIT :batchSize
    FOR UPDATE SKIP LOCKED
)
RETURNING *
```

```sql
CREATE INDEX ix_notification_delivery_claim
    ON notification_delivery (channel, priority DESC, next_attempt_at, id)
    WHERE status IN ('PENDING', 'FAILED');
```

The column order **is** the query's, and every part of it is load-bearing:

- **partial on the two claimable statuses** — the vast majority of rows in this table are settled and
  will never be claimed again. An index covering them would be mostly dead weight: larger to scan,
  slower to maintain on every insert, and no use to the only query that is hot.
- **`channel` leads** because it is the only equality predicate, which is what makes the scan a range
  rather than a filter. It is also why the claim is per channel at all: claiming across channels would
  let a stalled SMTP server's backlog starve the webhook channel behind it, and would make a
  per-provider rate limit impossible to honour.
- **`priority DESC, next_attempt_at`** match the `ORDER BY` exactly, so Postgres can stop after `LIMIT`
  rows instead of sorting the whole backlog — the difference between a claim that is constant-time in
  the queue depth and one that is not.
- **`id` last** makes the ordering total. Without it two rows with the same priority and timestamp
  could come back in different orders on different replicas: not wrong, but it makes a concurrency bug
  impossible to reproduce.

`priority` is stored as an **orderable integer weight**, not the enum name. As a `varchar`,
`ORDER BY priority DESC` would sort `NORMAL` ahead of `HIGH` alphabetically — silently inverting the
lanes. `scheduled_at` is deliberately *not* in the predicate: due-ness is decided by
`next_attempt_at` alone, set to `max(scheduledAt, now)` at fan-out, which keeps the claim to one range
condition and removes the class of bug where the two disagree.

Two more partial indexes exist for the SLO gauges (`created_at` over the claimable set) and the stale
sweeper (`claimed_at` over `CLAIMED`).

## Promoted: both platform gaps are filled

Two capabilities lived behind narrow interfaces here because the platform had neither, and **without either
of them this service double-sends at more than one replica** — they were never optional polish. The narrow
interface was the point: it is what made promoting each of them a move rather than a rewrite. Both have now
been promoted, and this section is the record of where they went.

### 1. `IdempotencyStore` → **`idempotency-spring-boot-starter`**

This service no longer has a dedup store of its own. `IdempotencyStore`, `PostgresIdempotencyStore`,
`IdempotencyRecordEntity`, `IdempotencyRecordRepository`, `IdempotencyQueryRepository(Impl)` and the
`notification_idempotency` table are gone; the claim lives in `idempotency_claim` and this service calls that
module's store.

The correctness was always in one statement, and it survived the move unchanged:

```sql
INSERT INTO idempotency_claim (…)
VALUES (…)
ON CONFLICT (scope, idempotency_key) DO UPDATE SET …
RETURNING request_id, state, …
```

Read-then-insert passes a single-threaded test and fails exactly here: two replicas handling the same record
both read "no row", both insert, and one takes a constraint violation that aborts a transaction which has
already written a request and its deliveries. `DO UPDATE` makes the loser block on the winner's row lock and
then read the committed winner — no exception, no rollback, no double send. `DO NOTHING` would not do either:
it returns no row on conflict, and in `READ COMMITTED` the re-select can still miss a row whose inserting
transaction has not committed.

Scoped by ingress, because a Kafka record key and an HTTP `Idempotency-Key` come from different namespaces and
a collision between them would silently drop a genuine request. **The two scope names stay here**, in
`ludwig.notification.idempotency`, for the same reason the two lock names did: a scope is this service's
coordination contract between its own two ingresses and means nothing to any other service.

**Two API-compatibility decisions, both taken deliberately rather than inherited.**

**The store stays directly callable, and this service does not use the module's HTTP filter.** That filter
replays a completed duplicate's response, which is the better default for most APIs and the wrong one here:

- `NotificationRequestResponse.duplicate` is a documented field of this service's API, and the **200-versus-202
  distinction below is built on it** — a duplicate answers 200 with the original request so a caller retrying
  after a timeout can tell whether their retry did the work. The filter's natural behaviour is to replay the
  original **202** instead, which would silently change what a retrying caller is told. The flag is kept and
  the contract is unchanged.
- `BatchSendRequest` carries a key **per item, in the body**, precisely because a header cannot carry one value
  per item. No header-based filter can serve that endpoint, so adopting the filter for `POST /` alone would
  leave the two endpoints deduplicating differently — which is worse than either choice made consistently.

So `NotificationServiceImpl` claims in `TRANSACTIONAL` mode, which is exactly what the local primitive did:
the claim is written in the same transaction as the request row and its deliveries, and a rollback frees the
key. That is required rather than convenient — a claim that committed independently would leave a key
permanently reserved for a request whose transaction then rolled back, and the retry of that request would be
rejected as a duplicate of something that does not exist.

What changed for a deployment:

| | was, here | is, in `idempotency-spring-boot-starter` |
|---|---|---|
| Table | `notification_idempotency` | `idempotency_claim`, with state, lease, fingerprint and a stored response this service does not use |
| TTL | `ludwig.notification.idempotency.ttl` | `ludwig.idempotency.ttl`, **per scope** — so the Kafka ingress can hold keys for a week while REST holds them for a day, which this service could not express before |
| Purge | a step in `RetentionScheduler`, under `LockNames.RETENTION` | the module's own batched job under `job-core`'s lock, with the lease renewed **between batches** — which this service's single-statement step did not do |
| Claim modes | one, transactional and undocumented as such | two, named and required at the call site |
| Expiry | evaluated by the purge only, so a key past its TTL still deduped until the purge next ran | evaluated **inside the claim statement**, so the TTL means what the configuration says |
| Metrics | none | claims, replays, failures, **fingerprint mismatches** and purged rows |

**The migration moves the rows.** `db.changelog-master.xml` includes the module's changelog *above*
`0006-fold-idempotency-into-starter.xml`, which drops the old table — and that order is load-bearing rather
than cosmetic. The module's changelog carries the changeset that moves every row across; dropping first would
discard every in-flight key, and **every in-flight key is a claim on work that has been done**, so every one
of them is a notification this service would send to a real person a second time, during the very deploy that
introduces the module whose purpose is to prevent it. The drop's precondition is the reconciliation an operator
would otherwise perform by eye: zero rows in the old table that are not in the new one.

### 2. The distributed lock → **`job-core`**

This service no longer has a lock of its own. `DistributedLock`, `PostgresDistributedLock`,
`LockLeaseService`, their repositories and entity, and the `notification_lock` table are gone; the maintenance
jobs run under `job-core`'s `RunLock`, backed by `job_run_lock`. The two lock *names* stay here in
`LockNames`, because a lock name is this service's coordination contract between its own replicas and means
nothing to any other service.

The reasoning that made a lock necessary here is unchanged and now lives in `job-core`. The delivery poller
**does not use it and must not**: `SKIP LOCKED` already partitions the queue, so three pollers claim disjoint
batches with no coordination, and putting a lock there would throw away two thirds of the throughput to solve a
problem that does not exist. What needs it is every job defined over a *set* of rows rather than over each row
independently: the digest collapse (three replicas → three digests for one person), the retention purge, and
the suppression compaction.

Still a leased row rather than `pg_try_advisory_lock`, for the same reason it always was. An advisory lock is
held by the database *session*, and with a connection pool the session returns to the pool the moment the
statement finishes — so holding one across a multi-minute digest run means pinning a pooled connection and
trusting nothing in the stack quietly returns it. It is also invisible: an operator asking "why has the digest
not run for an hour?" has nothing to look at. A row with an explicit `expires_at` is inspectable, survives the
connection, fails over on a configured timeout rather than an accidental one, and can be broken by hand.

What changed in the fold, and why the platform's version won on every axis where the two differed:

| | was, here | is, in `job-core` |
|---|---|---|
| Table | `notification_lock`, id **is** the lock name, release **deletes** the row | `job_run_lock`, surrogate id + unique `lock_name`, release nulls the owner and expires the row — so the table is bounded by lock names and acquisition has one shape instead of two |
| Transactions | `@Transactional(REQUIRES_NEW)` on a separate `LockLeaseService` bean, which existed **only** to dodge Spring's proxy self-invocation trap, and which coupled leases to a `PlatformTransactionManager`, a JPA entity, entity scanning and a generated Q-type | a short auto-commit JDBC connection of its own — one class, no proxy caveat, no JPA at all |
| Fencing | renew matched `owner` only, so a lease that lapsed and was re-acquired **by the same pod** renewed as though nothing had happened | renew matches `owner` **and** `run_id`, so a superseded run is told to stop — a correctness property this service did not have |
| Lease TTL | `ludwig.notification.locks.lease` | `ludwig.job-core.lock.default-lease`, one failover time for the platform |
| Metrics | `notification.locks{lock,outcome}` | `ludwig.job.lock.acquisition{lock,acquired}` plus `ludwig.job.lock.lost{lock}` — a failed renewal, the event worth alerting on, which **neither** implementation used to record |

### What is left here that could still be promoted

The **cluster-wide rate limiter** (`notification_rate_limit_window`, `PostgresChannelRateLimiter`). It is
genuinely general — an in-process token bucket is wrong at more than one replica and wrong in the direction
nobody notices, because three pods each holding a 100-per-minute bucket send 300 per minute — and nothing about
it is specific to notifications. It has not been promoted because no second consumer has asked for it, and a
platform module with one consumer is a module whose interface has been guessed rather than designed.

The **template revision table** is not a candidate: it is about FreeMarker sources this service owns, and a
platform module for it would have exactly one possible user.

## The request contract

`POST /api/v1/notifications` — roles `NOTIFICATION_SENDER` or `NOTIFICATION_ADMIN`.

| Field | Required | Meaning |
|---|---|---|
| `templateKey` | ✔ | template family, e.g. `password-reset`. Lower-case, path-safe |
| `category` | ✔ | what preferences are expressed against, e.g. `security` |
| `categoryClass` | ✔ | `TRANSACTIONAL` (bypasses preferences and quiet hours) or `MARKETING` |
| `priority` | ✔ | `HIGH`, `NORMAL` or `BULK` |
| `channels` | ✔ | `EMAIL`, `CHAT`, `WEBHOOK` — at least one |
| `recipients` | ✔ | up to 500; each names **exactly one** of `userId` or `address` |
| `variables` | | template variables, merged under each recipient's own |
| `scheduledAt` | | earliest send time; a past value means now |

`Idempotency-Key` is a header, not a body field, so it is visible to a proxy and cannot be confused
with the payload it protects. Omitting it is legitimate — a fire-and-forget caller who accepts a
duplicate on retry — and forcing one would only produce random values that protect nothing.

**There is no way to submit rendered text.** A caller that could would be deciding this service's
wording, bypassing localization entirely, and turning every copy change into a coordinated release
across every calling service.

**202, not 201.** What has been created is a queued intention; whether anything reaches anybody is
decided minutes later by a provider this service does not control. A duplicate answers **200** with
the original request, so a caller retrying after a timeout can tell whether their retry did the work.

### Reading a request back

`GET /api/v1/notifications/{id}` — roles `NOTIFICATION_SENDER`, `NOTIFICATION_SUPPORT` or
`NOTIFICATION_ADMIN` — answers the request and every delivery it fanned out into. This is the
resource the `Location` header of the 202 points at: an asynchronous accept is only a useful answer
if the location resolves, and a caller whose HTTP call timed out *after* the request was written has
no other way to find out what happened to it.

It is scoped as well as role-gated, through the same `DataAccessGuard` the delivery history uses. A
request names recipients, so a sender holding an id must not be able to read another caller's — the
shipped policy gives `NOTIFICATION_SENDER` the `OWN` scope, `NOTIFICATION_SUPPORT` its tenant, and
`NOTIFICATION_ADMIN` everything.

### Submitting a batch

`POST /api/v1/notifications/batch` — same roles as a single submit.

```json
{ "items": [
    { "reference": "order-4711", "idempotencyKey": "shipped-4711",
      "request": { "templateKey": "order-shipped", "…": "…" } },
    { "reference": "order-4712", "idempotencyKey": "shipped-4712",
      "request": { "templateKey": "order-shipped", "…": "…" } } ] }
```

It exists because there is no broker: a caller that would have produced a hundred records to a topic
should not have to open a hundred connections to replace it. It is a transport optimisation and
nothing more — each item goes through the same application service, the same validation and the same
idempotency, and an item submitted here is indistinguishable afterwards from one submitted alone.
The dedup key moves into the body because one header cannot carry a value per item.

**This is not "one request with many recipients".** That is *one* notification — one template, one
category, one key — delivered to several people, and it is atomic. A batch is several unrelated
notifications travelling together, and each succeeds or fails on its own. A caller that wants
all-or-nothing wants the first shape, which the single endpoint already provides.

**Always 200**, whatever the items did, with per-item results:

```json
{ "accepted": 1, "rejected": 1, "results": [
    { "index": 0, "reference": "order-4711", "request": { "id": "0f8a…", "state": "FANNED_OUT" } },
    { "index": 1, "reference": "order-4712",
      "problem": { "type": "urn:notification:error:…", "status": 404, "code": "…" } } ] }
```

There is no status code that describes a mixed outcome: 202 would claim every item was accepted and
a 4xx would claim none was. The status answers "was the batch understood", the body answers "what
happened to each item". A failure is a `ProblemDetail` — the same object, from the same pipeline,
that the single endpoint would have returned for the same input, so a caller writes one error
handler rather than two.

Each item is **its own transaction**. One bad item leaves the ones before it committed and delivered,
which is the whole reason to offer a batch rather than telling callers to loop. `ingress.rest.fail-fast`
turns that off for a caller that wants the first failure to stop the rest; it ships off. A batch
above `ingress.rest.max-batch-size` (100) is refused with **413** carrying both numbers, so a client
can resize itself rather than read prose.

### Everything else

`POST /preview` (render without sending), `GET /deliveries` (OData search),
`GET /deliveries/{id}` + `/history` + `/content`, `POST /deliveries/{id}/retry|cancel`,
`GET|POST|DELETE /suppressions`, and `POST /receipts` for provider callbacks. Full schemas at
`/swagger-ui.html`.

There are no recipient or preference endpoints. A recipient's address is the OIDC provider's to
change and their preferences are the account service's; this service having written to either would
have been the second store the whole design exists to avoid.

### The platform operation envelope

`NotificationRequestResponse` **gains** an `operation` member, and loses nothing. This is a published
API with consumers outside this repository, so replacing `state`, `createdAt` or `rejectionReason`
would be a versioned API change; adding a member is not, and every existing client keeps reading
exactly what it read before.

```json
{
  "id": "...", "state": "FANNED_OUT", "duplicate": false,
  "deliveries": [ ... ],
  "operation": {
    "id": "...", "status": "SUCCEEDED", "detail": "FANNED_OUT",
    "progress": { "completed": 3, "unit": "deliveries" },
    "submittedAt": "...", "finishedAt": "...",
    "result": { "href": "/api/v1/notifications/..." },
    "correlationId": "..."
  }
}
```

The mapping onto `web-core`'s vocabulary, and why each arm is what it is:

| `state` | `operation.status` | why |
|---|---|---|
| `ACCEPTED` | `PENDING` | persisted and its key registered; nothing fanned out yet |
| `FANNED_OUT` | `SUCCEEDED` | the operation is the **request** - "did my call become deliveries" - not the deliveries |
| `REJECTED` | `FAILED` with code `error.notification.request.rejected` | nothing was enqueued and nothing will be |

`FANNED_OUT` mapping to `SUCCEEDED` is the two-aggregate split doing its job, not a shortcut. A
delivery that later bounces is a fact about a delivery; folding it back into the request's state would
mean deriving the request's status from its deliveries and keeping the derivation current, which is
precisely the coupling the split exists to avoid.

`CANCELLED` and `EXPIRED` are never produced here. A request is fanned out in one transaction within
milliseconds of being accepted, so there is no window in which cancelling it would mean anything, and
nothing expires a request row.

Two header changes a client will notice:

- **`GET /{id}` of a request that has not finished fanning out carries `Retry-After`**, from
  `ludwig.notification.ingress.rest.retry-after` (default `1s`).
- **A duplicate submit now carries `Retry-After` too**, when the original it resolved to is still
  `ACCEPTED`. It is still a `200` and still not a `202`: the caller is being handed somebody else's
  in-flight work, and nothing new was started. That distinction is the one
  `idempotency-spring-boot-starter` is careful about, and this endpoint does not blur it - a `409`
  there would mean "your duplicate found work in flight", which is what the filter answers, and a
  `202` would claim a second request had been accepted.


## Running without a broker

This platform has no Kafka yet, and the service is configured for that rather than half-configured
for the topology it will eventually have. Four things that would otherwise need a broker are off by
default, each with the same switch-on note in `application.yml`:

| Off | What it costs today | Turning it on |
|---|---|---|
| `ingress.kafka-enabled` | nothing — REST carries the same traffic through the same service | one flag once a topic exists |
| `identity.kafka.enabled` | nothing *feeds* `security_user`, so a `userId` resolves to an address only if something else wrote that row | one flag, plus the OIDC stream |
| `notification.events.enabled` | no outbound lifecycle events | one flag **and** a transport — see below |
| the user-settings module | per-recipient opt-outs, quiet windows and digest cadence read as unset | a POM change and a config block — see [Where preferences come from](#where-preferences-come-from) |

Two of those deserve more than a row.

**Addresses.** With nothing feeding the identity projection, a request naming a `userId` finds no
row and produces a terminal delivery rather than a send. A caller that cannot rely on the projection
names the destination literally instead — `recipients: [{"address": "ada@example.com"}]` — and every
other rule applies identically: suppression, rate limits, templates, retries, receipts. The tables
are still created and still read, so the day the stream arrives nothing changes but the data.

**Lifecycle events.** They are published through the outbox, whose default route is `KAFKA`. Left
enabled with no broker, every delivery would write an outbox row the dispatcher could never send and
the table would grow until somebody noticed — so they ship **off**. The outbox module also has a REST
dispatcher, which is the way to have an event stream without a broker:

```yaml
ludwig:
  notification:
    events: { enabled: true }
  outbox:
    default-route: { transport: REST, destination: notification-events }
    rest:
      endpoints:
        notification-events: https://subscriber.internal/hooks/notifications
```

Nothing here is a permanent shape. Each of the four is one flag away from the brokered topology, and
the code paths behind them are the ones that already ship — not alternatives maintained in parallel.

## The Kafka topic

**Off by default** (`ludwig.notification.ingress.kafka-enabled`); this section describes the ingress
as it behaves once a broker exists. Default topic `platform.notifications.requests`, group
`notification-service`, dead letters to `platform.notifications.requests.dlt`.

The container now comes from **`messaging-spring-boot-starter`**, and `NotificationMessagingConfig` is
three lines naming this service's payload type and its topic. That is deliberate rather than incidental:
this configuration was the only one of the platform's three Kafka consumers that had been reasoned about,
so its decisions became the platform's defaults and it became the caller rather than being replaced by
one. The effective configuration is unchanged — `RECORD` acknowledgement, `ErrorHandlingDeserializer`
around key and value, auto-commit off, a `JsonDeserializer` with type headers off, 1s tripling to a 30s
cap, `DeserializationException` and `MessageConversionException` non-retryable, `<topic>.dlt` on a
broker-chosen partition — with three exceptions, all of which are fixes:

- **The attempt budget is four deliveries, where it used to be five.** Spring's
  `ExponentialBackOff.maxAttempts` counts the intervals it hands out, which is the number of retries, so
  the old `MAX_ATTEMPTS = 4` produced five deliveries under a comment reading "Attempts before a record is
  dead-lettered, including the first". Four is what that comment said; the code now matches it, and an
  integration test counts the deliveries.
- **A poison record can now actually be dead-lettered.** The old configuration handed Boot's
  `KafkaTemplate` to its `DeadLetterPublishingRecoverer`, and a record that failed to deserialize is
  republished as raw bytes, which that template's serializer cannot send: the publication failed, the
  offset was committed, and the record was lost. Invisible, because nothing had ever put a malformed
  record on this topic in a test with a real broker.
- **The correlation id now reaches the listener.** `NotificationRequestListener` reads it from the
  correlation context, and the observability starter's interceptor that populates it was only ever applied
  to Boot's own container factory — so every request consumed from this topic was logged under a
  correlation id of its own. The listener's code was right; nothing had connected the two.

The consumer also gains, for the first time, a lag metric and a `ludwig.messaging.silent` gauge — see that
module's README.

```json
{
  "idempotencyKey": "pwreset-4711",
  "templateKey": "password-reset",
  "category": "security",
  "categoryClass": "TRANSACTIONAL",
  "priority": "HIGH",
  "channels": ["EMAIL"],
  "recipients": [{"userId": "8f2c…", "variables": {"firstName": "Ada"}}],
  "variables": {"resetLink": "https://…", "expiresInMinutes": 15},
  "scheduledAt": null
}
```

The payload type is **separate from the REST DTO** and the architecture rules enforce it. A topic and
an HTTP endpoint are versioned on different clocks by different people: the REST contract can change
with a deprecation window and a client release; the topic contract has to stay readable by every
producer that has not been redeployed. Sharing one class means a change safe for one is silently
forced on the other.

The consumer is tolerant where tolerance is safe and strict where it is not. An unknown field is
ignored; an unknown enum value falls back with a warning rather than failing every notification a
producer sends; a recipient naming neither `userId` nor `address` is dropped so one malformed entry in
a hundred does not discard the other ninety-nine. But offsets are committed **after** the listener
returns (`AckMode.RECORD`), because with auto-commit a database outage would advance the offset past
every notification that arrived during it.

## How to author a template

Templates are files, addressed by a directory tree:

```
<templateKey>/<channel>/<locale>/subject.ftl
<templateKey>/<channel>/<locale>/body.html.ftl   ← required
<templateKey>/<channel>/<locale>/body.txt.ftl
```

e.g. `password-reset/email/ru/body.html.ftl`. Resolution tries the exact locale, then the language
without its region, then the configured default — so `ru-RU` finds a `ru` template, and a locale with
no translation gets the default language *in full* rather than a half-translated document.

Channel and locale are part of the *address*, not parameters inside one template, because they change
the whole document: an email is a subject plus an HTML body plus a text alternative, a chat message is
one short line, and the Russian version of either is not the English one with words substituted.

### Rendering is strict, and that is the feature

A missing variable **fails the render**. FreeMarker can be configured so that `${firstName}` on a
missing variable produces an empty string — and then a password-reset email goes out reading *"Hello ,
your code is ."*: accepted by the provider, delivered to the mailbox, counted as a success on every
dashboard, and discovered days later by a customer who could not log in.

A genuinely optional field opts in explicitly:

```ftl
<p>Hello<#if recipient.displayName??> ${recipient.displayName}</#if>,</p>
<p>Use <a href="${resetLink}">this link</a>; it expires in ${expiresInMinutes} minutes.</p>
<p>${middleName!""}</p>
```

`resetLink` and `expiresInMinutes` carry no default, so a caller who omits them finds out. A render
failure is **terminal**: the variables were fixed when the request was accepted, so retrying eight
times over two hours produces eight identical failures and only delays the operator learning about it.

Every template sees one reserved key, `recipient`, carrying `userId`, `displayName`, `locale` and
`timezone`, plus `locale` at the top level holding the locale actually resolved. The preview endpoint
supplies a clearly-marked sample recipient so a preview of a real template renders exactly as the send
would.

### Versioning and hot reload

Mount the directory and set `ludwig.hotreload.freemarker.template-directory`; a wording change is then
a ConfigMap rollout, not a release, and the change is picked up on the next render.

The cost of that is that the directory is not a historical record — by the time somebody asks what
wording a customer received in March, the file has been edited twice. So every render hashes the
source it used, records the hash on the delivery (`template_version`), and the first time a hash is
seen it is written to `notification_template_revision` with the source and the next revision number.
A delivery is always traceable to exact text, and nobody has to declare a version by hand.

## How to add a channel

Add a bean. Nothing in the dispatch path enumerates channels or switches on a type.

```java
@Bean
NotificationChannel smsChannel(RestClient.Builder builder, NotificationProperties properties) {
    return new SmsChannel(/* … */);
}
```

```java
public interface NotificationChannel {
    boolean supports(ChannelType channelType);
    String name();
    DeliveryResult send(RenderedNotification notification);
}
```

Then add the constant to `ChannelType`, its persistence twin `ChannelKind` and its wire twin
`ChannelTypeDto` — MapStruct fails the build if the three drift apart — and a `channels.sms` block to
`NotificationProperties`. `ChannelType`'s constructor also requires a `ChannelClass`, so a transport
added without one does not compile; read [the inbox section](#the-inbox-the-one-passive-channel) for
what that declaration means before choosing.

**This is the whole story for an interrupting channel, and deliberately not for a passive one.**
`IN_APP` has no `NotificationChannel` bean at all — see below.

The contract the implementation must honour:

- **No transaction is open** when `send` is called, and it must not open one. A provider call inside a
  transaction holds a pooled database connection for the duration of somebody else's network, which is
  how one slow SMTP relay drains the pool and takes the ingress down with it.
- **Everything needed is in the argument.** A channel never reads the database, which is what makes it
  unit-testable without a persistence context.
- **Classify the failure.** Returning the wrong `FailureClass` is the most consequential mistake an
  implementation can make: too retryable and a malformed address is attempted eight times over two
  hours; too terminal and a restarting server permanently dead-letters everything in flight.
- **Time out.** The lease is the backstop, not the control. A hung provider with no read timeout takes
  the poll thread with it.

Throwing is allowed and is treated as retryable: an unclassified escape is more likely a bug or a blip
than a permanent rejection, and a pointless retry costs less than a silently dead-lettered
notification.

The three shipped channels classify like this:

| | retryable | terminal |
|---|---|---|
| **SMTP** | 4yz reply, connect/TLS/timeout failure, *authentication failure* | 5yz reply, `SendFailedException` naming invalid addresses, unparseable message |
| **Chat / webhook** | 5xx, 408, 425, 429, any `IOException` in the cause chain | every other 4xx, 3xx, unserializable payload, non-HTTP(S) URL |

An SMTP authentication failure being **retryable** is deliberate: it looks permanent and usually is,
but credentials come from Vault through the hot-reload module, so a rotation this pod has not picked
up yet presents exactly this way — and dead-lettering the whole in-flight queue on a credential
rotation is far worse than a few wasted retries. Conversely HTTP 401/403 are **terminal**, because
dead-lettering makes a broken credential visible in minutes whereas retrying hides it behind a queue
that slowly stops moving; those dead letters are replayable once it is fixed.

## The inbox: the one passive channel

`IN_APP` notifies somebody *inside the product*: the notification is stored, and its recipient reads
it back when they choose. It exists because every other transport here pushes a message at a person
and is done, which leaves nobody to notify when a recipient has declined every push channel, has no
verified contact address, or is simply at their desk.

```console
$ curl -s https://notifications.internal/api/v1/inbox/unread-count -H "$AUTH"
{"unread":3}

$ curl -s "https://notifications.internal/api/v1/inbox?\$top=1" -H "$AUTH"
{"content":[{"id":"7c3f…","category":"account","createdAt":"2026-10-09T09:12:04Z","read":false}],
 "offset":0,"size":1,"totalElements":3}

$ curl -s -X POST https://notifications.internal/api/v1/inbox/7c3f…/read -H "$AUTH"
{"id":"7c3f…","read":true,"readAt":"2026-10-09T09:40:11Z","seenAt":"2026-10-09T09:40:11Z"}
```

### It is not a channel bean, and that is the carve-out

There is **no `NotificationChannel` implementation for `IN_APP`**, and adding one fails the build
(`InAppSettlementIT#noTransportSupportsInApp`). The reason is the transport contract itself: an
implementation may not open a transaction and may not touch the database, because a provider call
inside a transaction holds a pooled connection for the duration of somebody else's network — which
is how one slow relay drains the pool. The work queue in front of it exists for the same reason.

Neither hazard exists here. The "provider" is the same PostgreSQL the fan-out transaction is already
writing to: there is no third-party network, no timeout to bound, and no failure a retry could fix.
So an in-app delivery is **settled in the transaction that accepted the request** — created
`ACCEPTED` and moved straight to `DELIVERED`, alongside the `SUPPRESSED`, `BATCHED` and `DEAD`
settlements the fan-out already performs — and is never written in a state the claim query selects
(`InAppSettlementIT#claimIgnoresInAppDeliveries`). The caller's `202` therefore reports a delivery
that is already terminal, and the item is readable the instant it returns.

`DELIVERED` and not `SENT`: `SENT` means "handed to a provider that has not confirmed", and there is
no provider to confirm.

### `INTERRUPTING` vs `PASSIVE`, and why the dimension exists

Every transport declares a `ChannelClass`, as a mandatory constructor argument of `ChannelType`.
The test to apply: **does delivery reach somebody who is not asking for it right now?**

| | opt-out | quiet hours | digest | suppression list |
|---|---|---|---|---|
| `EMAIL` / `CHAT` / `WEBHOOK` — `INTERRUPTING` | yes | yes | yes | yes |
| `IN_APP` — `PASSIVE` | **yes** | no | no | no |

Three of those four rules are justified by the same premise — that delivery interrupts somebody —
and that premise is false for the inbox. Each is switched off for its own reason: a quiet window
exists so that nobody is woken, and an item that waits wakes nobody; a digest exists to reduce a
count of interruptions, and folding an inbox is the inbox's own presentation decision; and a
suppression entry is a fact about a delivery *address*, of which a passive channel has none.
Per-category opt-out still applies in full — a recipient may decline a declinable category here
exactly as anywhere else.

The dimension exists so that is **one declaration** rather than an `IN_APP` branch in six
evaluators. An ArchUnit rule fails the build if anything in the preference path reads the `IN_APP`
constant instead of asking the classification. What no build can check is whether a transport's
declared class is *true* — that is a statement about the world, exactly as a cache's `CachePurpose`
is, and it is the one mistake the module cannot detect for you.

### The inbox item is a second aggregate

| | `notification_delivery` | `notification_inbox_item` |
|---|---|---|
| means | what we tried to do | what the recipient has |
| written by | this service | its recipient |
| shape | a work-queue row, claimed continuously, kept narrow | a document, mutated by its owner |
| retention | 90d **from creation** | 90d **from being read** |

This is the same argument the service already makes one level up for why retry state lives on the
delivery and not the request. One table holding both roles would force the retention purge to give a
single answer to two different questions, and the delivery deliberately is not an `AuditedEntity`,
so a recipient's own write to it would not be audited the way a user-owned mutation should be. An
ArchUnit rule fails the build if a read-state field ever appears on a delivery entity.

Content is a separate table for the same two reasons the delivery's is — the item row is what every
inbox query scans, and content is the purge's one cheap target — but it is **not** the delivery's
content table, and that is the point. `notification_delivery_content` is purged at `content-ttl`,
seven days, with a startup check that a body never outlives its delivery; an unread inbox item has
to survive somebody's holiday. A rule fails the build if the settlement path reaches that table.

Rendering also happens at fan-out rather than at send time, which is the opposite of what this
service does everywhere else. A passive channel has no later send step for that reasoning to attach
to, and deferring the render to read time is not merely awkward but impossible: the variable map is
scrubbed at `recipient-data-ttl`, so an item still unread in week three could no longer be rendered
at all. The consequence is accepted rather than hidden — an in-app notification carries the wording
that was current when it was produced, which is the same promise already made about a sent email.

### The read API, and the three defences on it

```
GET  /api/v1/inbox                     the caller's own items, newest first, dismissed excluded
GET  /api/v1/inbox/unread-count        for a badge: a number, no content
GET  /api/v1/inbox/{id}                one item, with its rendered body
POST /api/v1/inbox/{id}/seen           shown in a list — not the same as opened
POST /api/v1/inbox/{id}/read           also marks seen; idempotent
POST /api/v1/inbox/read                mark all read; reports how many moved
POST /api/v1/inbox/{id}/dismiss        leaves the list, stays retrievable by id
GET  /api/v1/filter-metadata/notification-inbox    what $filter may name
```

`isAuthenticated()` and no role: reading one's own notifications is not a privilege anybody grants,
and a role here would either be granted to everybody — and so mean nothing — or be forgotten for
somebody who then silently stops seeing their notifications. The row-level rule is the owner
predicate, and it lives where the rows are.

A caller can only ever reach their own inbox, and three independent mechanisms say so:

1. **No parameter to supply.** No owner path variable, no owner query parameter, and no owner field
   on any response that a client could send back.
2. **No filter to spell.** `ownerUserId` carries no `@Filterable`, so `$filter=ownerUserId eq …` is
   rejected as naming an unfilterable field — the same way a filter on a recipient address already
   is. An ArchUnit rule fails the build if the annotation is added.
3. **No status code to read.** A foreign item and an item that never existed return the **identical**
   404 — same type, same title, same wording. A 403 would be an oracle: repeated against guessed ids
   it enumerates what the platform has told other people, and "has this person been told about X" is
   exactly the fact its subject would consider private. `InboxScopingIT` compares the two responses
   field by field rather than merely checking both are 404, because a well-meaning improvement to an
   error message is how that distinction gets reintroduced.

Any one of the three would be enough on a good day. Together they mean that removing one — which
somebody will eventually do, not knowing it was load-bearing — does not open the hole.

The transitions are `POST` rather than `PATCH` because they are named idempotent commands over a
derived state machine rather than partial edits of a document, and none of them is a long-running
operation: each completes inside its request, so there is no envelope, no status resource and no
`Retry-After`.

The three instants are **monotonic** — set once, never reset. A client that double-taps "read" must
not rewrite `read_at`, because that instant is what the retention window is measured from: a
notification read in January could otherwise still be sitting in an inbox in March because a retry
kept pushing its anchor forward.

### The fallback

```yaml
ludwig:
  notification:
    preferences:
      fallback:
        TRANSACTIONAL: IN_APP
```

Empty by default. When set, and when **every** channel a caller requested settled `SUPPRESSED` for
one recipient, one more delivery is created on the fallback channel — so a notification the platform
owner has declared undeclinable lands somewhere. Four conditions, each excluding a case that would
otherwise be wrong: every requested channel suppressed; a fallback declared for that category class;
the caller did not already ask for that channel; and **the recipient has not declined it either**.
The last one is what keeps the feature honest — a fallback that overrode an opt-out would turn *"I do
not want this category"* into *"you will get it somewhere else"*, which is worse than not delivering
it.

Keyed by category class in configuration, never a field on the request, for the same reason the
transactional bypass is a category property: as a request flag every calling service would set it.
Think hard before adding `MARKETING` — forcing campaigns into an inbox somebody has deliberately
emptied is the one configuration of this feature that is worse than not having it.

A delivery that settled `DEAD` — no destination at all — does **not** trigger the fallback. A refusal
is a preference the inbox respects; an unreachable address is an operational problem that falling
back would hide. Whether it should is an open question rather than an oversight, and a test pins the
current answer either way.

### Retention

| What | Property | Default | Anchor |
|---|---|---|---|
| Inbox items and their bodies | `retention.inbox-ttl`, `retention.inbox-content-ttl` | 90d | `read_at` / `dismissed_at` |
| Unread items | `retention.inbox-unread-max-age` | **unset** | `created_at` |

An item that has been neither read nor dismissed is **never** purged by age. The ceiling that does
discard one is a separate property, unset by default, and its purge is logged at `WARN` on its own
line and counted apart from the routine total — "we discarded N unread notifications" is the one
retention number an operator must not have to go looking for.

## Platform announcements

An announcement is one message for an audience defined by a *rule* — everybody, or everybody holding
a role — stored **once** and read by all of them. Release notes, an incident notice, a deprecation
warning.

```console
$ curl -X POST https://notifications.internal/api/v1/announcements -H "$AUTH" -d '{
        "category": "platform-incident",
        "audienceType": "ROLE", "audienceValue": "ADMIN",
        "templateKey": "platform-release",
        "variables": {"productName": "Ludwig", "version": "1.4.0"},
        "visibleUntil": "2026-11-10T08:00:00Z"
      }'

HTTP/1.1 202 Accepted
Operation-Location: /api/v1/announcements/7c3f…/email-run
{"id":"7c3f…","category":"platform-incident","audienceType":"ROLE","emailRunId":"a91e…"}
```

### Why this is not a notification to everybody

The obvious implementation is to enumerate the users and submit batches. It works, and the numbers
say why it is the wrong shape. One 4 KB release note to 50 000 people becomes 50 000 delivery rows,
50 000 inbox items and **50 000 copies of the same body** — about 200 MB. Worse than the size: a user
who joins tomorrow never sees it, a typo cannot be corrected, and the inbox's read-anchored retention
(right for "your invoice is ready") keeps the 40 000 copies nobody opened forever.

| | notification | announcement |
|---|---|---|
| shape | **N** documents, one reader each | **one** document, N readers |
| fan-out | on write | on read |
| rows for 50 000 people | 150 000 | **3** (one row, two locales) |
| a user created tomorrow | never sees it | sees it |
| correcting a typo | impossible | one `UPDATE` |
| retention | sweep N rows | delete 1, cascade |

### The audience is a predicate, resolved live

Two columns — `audience_kind` and `audience_value` — and no membership is ever stored. Visibility is
resolved **on every read** from the caller's own principal:

```
visible to me =  visible_from <= now < visible_until
             AND ( kind = EVERYONE  OR  (kind = ROLE AND value IN :myRoles) )
             AND NOT EXISTS (my dismissal marker)
```

`:myRoles` is already on the principal, so this is an `IN` over a short in-memory list — plain
QueryDSL, one index, **no join and no `UNION`**. A revoked role stops granting visibility on the next
request, with no job and nothing to go stale; somebody granted it sees the backlog. That is the same
argument `security-spring-boot-starter` uses for reading authorities from the projection rather than
from a token claim, and it is why a materialized audience is forbidden by an ArchUnit rule rather
than merely discouraged.

### Two feeds, not one, and the second reason is the important one

```
GET  /api/v1/announcements                  the caller's own, newest first, dismissed excluded
GET  /api/v1/announcements/outstanding-count  for a banner or a badge
GET  /api/v1/announcements/{id}             in the caller's language
POST /api/v1/announcements/{id}/dismiss     for this caller alone; idempotent
```

JPQL has no `UNION`, so a merged inbox-plus-announcement feed could not page, sort and count across
two sources without a third native-SQL carve-out. **And** the inbox's correctness rests on one
obviously-right predicate, `owner = me`; an announcement's visibility is *derived* from role
membership, so threading it in would put a reviewed security term on the endpoint every client polls.
An ArchUnit rule forbids the inbox path from depending on an announcement, so that stays true.

The cost is that a client shows two feeds and sums two numbers. That is probably also the right
product shape: an announcement is a banner or a "what's new" panel, not a line in the bell dropdown.

Reading needs only `isAuthenticated()` — **the audience predicate is the authorization**. A caller
without the role gets an empty page, not a 403, which is also why a foreign announcement and one that
never existed return the identical 404.

### Publishing: data on the request, policy in the configuration

```yaml
ludwig:
  notification:
    announcements:
      enabled: true
      allowed-audiences: [EVERYONE, ROLE]
      targetable-roles: [ADMIN, SUPPORT]     # roles that may be TARGETED, not who may publish
      max-visibility-window: 90d
      categories:
        platform-release:  { category-class: PLATFORM, channels: [IN_APP] }
        platform-incident: { category-class: PLATFORM, channels: [IN_APP, EMAIL] }
```

A publish names a **category** and carries no `channels` and no `alsoEmail`. That is the argument
`PreferenceEvaluator` already makes about the transactional bypass: anything an announcer can set,
every announcer sets to the most permissive value, because from inside any one team its own
announcement always looks important. Sending a hundred thousand emails should take a reviewed
configuration change, not a boolean.

`targetable-roles` is an allowlist and not "any role", for two reasons. A free-form role target is an
**enumeration primitive** — an announcer can discover which roles exist by publishing to guesses — and
some roles' membership is itself the sensitive fact. A refused kind and a refused role return the
*same* error carrying neither detail, so neither can be used to probe the configuration. Resolution
happens in exactly one method and an ArchUnit rule forbids a second construction path.

Publishing needs `ROLE_NOTIFICATION_ANNOUNCER`, deliberately **not** `NOTIFICATION_ADMIN`: an admin
inspects delivery history and retries dead letters, while an announcer originates a message to the
whole organisation. Support staff need the first and must not silently acquire the second.

### `PLATFORM`: undeclinable, but not urgent

| class | per-category opt-out | quiet hours |
|---|---|---|
| `TRANSACTIONAL` | bypassed | bypassed |
| **`PLATFORM`** | **bypassed** | **honoured** |
| `MARKETING` | honoured | honoured |

The empty cell in the old two-class model is the whole argument. Forced into `TRANSACTIONAL`, a
release note emails somebody at three in the morning; forced into `MARKETING`, half the estate has
opted out of the incident notice. Both answers are mandatory constructor arguments of
`CategoryClass`, so a fourth class cannot be added without somebody deciding both.

### One row per supported locale

The template is rendered once per `supported-locales` at publish — two rows today — so the count
follows the number of **languages**, never the audience. Every locale is rendered **before** anything
is stored, so a template that compiles in English and fails in Russian rejects the whole publish
rather than producing an announcement half the estate cannot read. A reader whose language has no row
gets the default in full, the same fallback rule the template resolver already applies.

Rendering at read time is not an option, for the same reason the inbox renders at fan-out:
`recipient-data-ttl` scrubs the variable map, so an announcement in its third week could no longer be
produced at all.

### Email fires from the announcement, and it *is* an operation

Where the category's channels include `EMAIL`, publishing starts a resumable fan-out that pages the
audience and creates **ordinary deliveries** — so the existing dispatcher, retry, backoff,
cluster-wide rate limit, suppression list and quiet-hours deferral all apply per recipient. A
broadcast is not a way to bypass any of them, and the cheapest guarantee of that is not having a
second send path at all. Deliveries go out at `BULK` priority, so one announcement cannot starve the
password resets people are actually waiting for.

Unlike anything in the inbox, this genuinely is a long-running operation — minutes, with progress,
cancellable — so it maps onto `web-core`'s `OperationResponse` through **this service's own** run
table, built with `OperationResponses`. The contrast with the inbox, which deliberately uses no
envelope, is the point.

```
GET  /api/v1/announcements/{id}/email-run               progress while running; terminal when done
POST /api/v1/announcements/{id}/email-run/cancellation   202 - the stop is REQUESTED
```

**Exactly-once comes from atomicity, not from the dedup key.** One batch creates its deliveries *and*
advances its keyset cursor in one transaction, so an interruption anywhere leaves the cursor where
the last complete batch left it and a resume has no overlap to reconcile — which is why there is no
per-recipient progress table. The existing unique `dedupKey` is the second line if two replicas ever
raced, and the batch's idempotency key is derived from the announcement and the cursor rather than
being random, which is what makes that backstop meaningful.

Cancellation is cooperative, answers **202 not 204**, and does **not** recall deliveries already
created — they are committed work, and dropping them would make the count the envelope already
reported untrue.

**The emailed audience is a snapshot; visibility stays live**, and the two are allowed to diverge:
an email is sent at an instant and cannot be unsent. Somebody who gains the role afterwards sees the
announcement and is not emailed; somebody who loses it mid-run may or may not be, depending where the
walk had reached. Re-checking membership per row would make a long run send to a different audience
than the one that was approved.

### Retention

| What | Property | Default | Anchor |
|---|---|---|---|
| Announcements, content, markers, run | `retention.announcement-ttl` | 90d | **`visible_until`** |

Anchored on the end of visibility, not on age: one published for next quarter has not started, and
one still showing must never vanish out from under its readers. The startup check refuses a window
shorter than `max-visibility-window`. Everything cascades from the one row, so the purge is a
constant number of statements whether the audience was ten people or the whole organisation.

## Preferences, quiet hours and suppression

Three mechanisms, checked in this order, and the order matters.

1. **Suppression list** — a fact about the *destination*: a hard bounce, a spam complaint, an
   unsubscribe. It has **no bypass at all**, not even for a transactional notification, because
   continuing to send to such an address damages the sending domain's standing with the mailbox
   provider for every other recipient. Checked at fan-out *and* again immediately before dispatch,
   because a delivery can sit behind a backoff for hours and a recipient can unsubscribe in that
   window.
2. **Preferences** — per recipient, per category, per channel. **This service does not own them**,
   and today does not have them: they are the user's settings, they belong to the account service,
   and until it publishes them every recipient resolves to the configured defaults (see
   [Where preferences come from](#where-preferences-come-from)). Absence means allowed — a
   preference that has not arrived must not silence a person.
   Two settings bear on one decision — the opt-out for the exact category and channel, and the
   blanket opt-out for the channel — and the **specific one wins whichever way it points**, which is
   what lets somebody say *"nothing at all, except order updates by email"* as two settings without
   the record of their original request being deleted. A `TRANSACTIONAL` category bypasses this
   entirely.
3. **Quiet hours** — evaluated in the *recipient's* timezone, never the server's, and handling a window
   that wraps midnight (22:00→07:00), which is what people actually configure. A marketing
   notification arriving inside the window is **deferred** to the end of it, not dropped: the caller
   was told the request was accepted, so something has to arrive.

All three apply to an **interrupting** channel. On the one passive channel only the second does —
see [the inbox](#the-inbox-the-one-passive-channel) for which of them is switched off and why each
has its own reason.

There are now **three** category classes, and the third exists because the first two cannot express
"undeclinable but not urgent" — see [platform announcements](#platform-announcements):

| class | per-category opt-out | quiet hours |
|---|---|---|
| `TRANSACTIONAL` | bypassed | bypassed |
| `PLATFORM` | bypassed | **honoured** |
| `MARKETING` | honoured | honoured |

The transactional/marketing split is a property of the **category**, not a flag on the request. As a
request flag every calling service would set it, and every one would set it to true — from inside any
one service its own notification always looks important. As a category property the decision is made
once, by whoever owns the notification catalogue, and a service wanting its campaign exempted has to
argue for it.

Only the **suppression list** stayed here when the preferences left, and the line between them is the
point: a bounce or a complaint is *delivery state*, derived from provider feedback that only this
service receives, and the user never chose it. Everything the user did choose lives with their other
settings.

## Where preferences come from

Locale, timezone, quiet hours, the digest choice and the per-category opt-outs are a **user's**
preferences, not a notification system's. On this platform the account service owns them. This
service therefore stores none of them — and, crucially, does not require them to work.

Everything on the dispatch path asks one interface:

```java
public interface RecipientPreferenceSource {
    StoredPreferences lookup(String userId, String tenantId);   // never null, never throws
    String describe();                                          // named in the startup log
}
```

Two implementations sit behind it, and nothing downstream can tell which answered — the types they
return (`OptOutMatrix`, `DigestMode`, `QuietHoursWindow`) are this service's own.

| | Source | What a recipient gets |
|---|---|---|
| **Today** | `ConfiguredPreferenceSource` | the configured defaults, plus whatever the request itself names; nothing opted out |
| **Later** | `UserSettingsPreferenceSource` | their stored preferences, read locally out of [`user-settings-spring-boot-starter`](../../sources/user-settings-spring-boot-starter/README.md) |

### Today: no preference store

The module is a **`provided`** dependency and is excluded from the executable jar, so it is genuinely
absent at runtime and `@ConditionalOnClass` genuinely answers false. Every recipient resolves to
`ludwig.notification.preferences.default-locale` / `default-timezone`, to no quiet window of their
own, and to "has declined nothing".

That last one is a deliberate direction rather than an oversight. A missing preference must not
silence a person: the failure mode of "assume opted out" is notifications nobody receives and nobody
can explain, and the failure mode of "assume not opted out" is visible to the recipient and fixable
the day the store arrives.

What still works without it is more than it looks:

- **the request's own hints.** A caller that already knows the recipient's language or zone passes
  them on the recipient — and they win over stored preferences anyway, because a locale taken from
  the interaction that triggered the notification is fresher than a profile setting last touched a
  year ago;
- **platform-wide quiet hours**, from `ludwig.notification.preferences.quiet-hours-*`;
- **the suppression list.** Bounces and complaints are this service's own data and are unaffected — a
  hard bounce is a fact about an address rather than a wish of its owner, which is why it is the one
  preference-shaped thing this service does own.

What does not work is the three statements a person actually made: per-recipient opt-outs, their own
quiet window, and their digest cadence.

### Later: switching the store on

Three edits, none of them on the dispatch path:

1. remove `<scope>provided</scope>` **and** the `spring-boot-maven-plugin` exclusion from `pom.xml`;
2. uncomment the `ludwig.user-settings` block in `application.yml`;
3. optionally set `ludwig.notification.preferences.source: USER_SETTINGS`.

`source` is the third switch and the one worth understanding. `AUTO`, the default, uses the module
when it is present *and* has a mode enabled, and falls back silently otherwise — which is right for a
migration, where the dependency lands in one release and the store is switched on in the next.
`USER_SETTINGS` makes that same fallback a **startup failure**. Set it once opt-outs are
load-bearing: at that point a deployment that quietly fell back would be mailing people who opted
out, with nothing in the logs saying why, and a pod that will not start is a far cheaper way to find
out. `NONE` pins resolution to configuration even where the module is present.

Whichever wins, the startup line says so:

```
Notification configuration validated: batch 25, lease PT10M, poll every PT2S;
  recipient preferences from configuration defaults (no preference store wired in)
```

### With the store: how it reads

The module runs in **projection mode**: it consumes the account service's change stream into a local
read-only replica and reads it there. It does **not** call the account service at dispatch — a queue
worker must not acquire a hot-path dependency on another service's availability, or an
account-service incident becomes a notification outage for notifications that have nothing to do with
accounts.

The keys are declared as typed constants on both sides. This service declares what it *reads*
(`NotificationSettings`), built from the platform's well-known definitions plus one opt-out per
declinable category per channel; the account service declares the same keys because it is the one
that *stores* them. A key this service reads and that one never writes resolves to its default, which
is the safe direction.

```yaml
ludwig:
  notification:
    preferences:
      source: USER_SETTINGS
      declinable-categories: [marketing, product-updates, digest-summary]
  user-settings:
    projection:
      enabled: true
      topic: platform.account.settings
```

Note what is *not* set there: `ludwig.user-settings.liquibase.enabled`. Unlike the outbox and
identity schemas, the settings changelog is **not** included by this service's master changelog — an
`<include>` naming a file inside a jar this deployment may not have would fail every migration, and
therefore every startup, rather than degrade. The module applies its own instead. The trade is a
second `DATABASECHANGELOG` history for three tables, which is cheaper than coupling the ability to
migrate to the presence of an optional jar.

### Opt-outs are a three-state answer, not a boolean

`UNSET`, `OPTED_IN`, `OPTED_OUT` — and the third state is what makes "nothing at all, except order
updates by email" expressible:

```
(all,           EMAIL) = OPTED_OUT     ← the blanket refusal, kept on record
(order-updates, EMAIL) = OPTED_IN      ← the one exception
```

Resolution asks the exact pair first and falls through to the blanket answer only when the specific
one is `UNSET`. Collapse the three into a boolean and the explicit opt-in becomes indistinguishable
from never having answered — so it falls through, and the one category the recipient asked to keep is
the one they stop getting. It is a silent failure with a satisfied user on the other end of it, which
is why the distinction is carried all the way from the store to `RecipientPreferences.optedOut`.

A category that is not listed in `declinable-categories` has no per-category key at all. It reads as
`UNSET` and can still be declined through the blanket opt-out — a category nobody declared is one
nobody has thought about, and "stop sending me things" honestly covers it.

### Resolved once, snapshotted onto the delivery

Preferences are resolved **once per recipient**, not once per setting and not once per channel, and
the resolved locale, timezone, address and quiet-hours decision are written onto every delivery row
the fan-out produced. Two reasons, both operational:

- a retry three hours later must not silently behave differently because a preference changed in
  between — the columns are snapshots and nothing on the retry path re-resolves them;
- when somebody asks why a message went out in the wrong language or at the wrong hour, the delivery
  row answers it, without a time-travel query against settings history.

The suppression list is the deliberate exception and *is* re-checked at every attempt, because a
bounce recorded between attempts has to stop the next one.

### Seeding the replica for the first time

A key nobody has ever written resolving to its default is safe, as above. A key somebody *did* write,
resolving to its default because the write never reached this replica, is not — and that is exactly the
state a fresh projection is in.

The projection is fed by a change stream, so on the day the module is first enabled its replica is
empty and the stream carries only what changes from now on. Somebody who opted out of marketing two years
ago and never touched the setting again produces no event, ever; this service reads a default, concludes
they never opted out, and mails them.

Fix it before the first fan-out, not after. Ask the account service to republish its stored state:

```
POST /admin/settings/backfill
{ "includeConsents": true }
```

Safe to run at any time and safe to repeat — every republished event carries the owner's original
timestamp, so a replica that is already current drops all of it. The same call is the recovery when this
service has been down longer than the topic's retention. See
[the backfill section](../../sources/user-settings-spring-boot-starter/README.md#seeding-a-new-projection-the-backfill).

## Priority lanes and rate limits

**Lanes.** Two claims per channel per cycle: the first restricted to `HIGH` and capped at
`queue.high-priority-reserve`, the second taking the remainder at any priority. Ordering by priority
alone is *not* enough — a bulk backlog of a hundred thousand rows is also, eventually, the *oldest*
work, so one query ordered by priority then age still spends the whole batch on it whenever no
high-priority row happens to be due at that instant. A reserved pass guarantees the capacity rather
than hoping for it.

**Rate limits.** Cluster-wide, in a database counter, reserved in batch before the claim. An
in-process token bucket would be wrong at more than one replica and wrong in the direction nobody
notices: three pods each holding a 100-per-minute bucket send 300 per minute, so the number in the
configuration file would mean nothing and would change meaning every time the deployment scaled. A
provider that rate-limits us does not care how many pods we run.

Unused permits are returned at the end of the cycle, so a quiet channel does not burn its window on
empty batches.

## Behaviour at three replicas

| Component | Behaviour | Why it is safe |
|---|---|---|
| REST ingress | all three serve | stateless |
| Kafka consumer | one group, partitions divided | the broker assigns; the idempotency claim covers redelivery during a rebalance |
| Fan-out | all three | request + idempotency claim + deliveries commit in one transaction; the unique `dedup_key` index is the last line of defence |
| **Delivery poller** | **all three, no coordination** | `SKIP LOCKED` makes the claims disjoint; the per-delivery lease makes ownership explicit |
| Rate limiter | shared counter | `FOR UPDATE` inside the reservation serializes the three; the sum never exceeds the limit |
| Stale sweeper | all three | a conditional bulk update — whoever runs first reclaims, the others match nothing |
| **Digest collapse** | **exactly one**, under `job-core`'s `RunLock`, name `notification-digest` | defined over a *set* of rows; three replicas would send three digests to one person |
| **Retention purge** | **exactly one**, under `RunLock`, name `notification-retention` | idempotent, but three would contend on the largest table at the worst moment |
| Outbox publisher | all three | the outbox module's own `SKIP LOCKED` poller |

Failure modes and what recovers them:

- **Graceful shutdown** — the poller cancels its schedule *without interrupting* a running cycle (an
  interrupted provider call may already have succeeded, and abandoning it guarantees a duplicate on
  retry), waits for the cycle bounded by half the lease, then hands its remaining leases back. Only
  rows still `CLAIMED` are released, so a delivery settled in the same cycle is not dragged back.
- **`SIGKILL`** — nothing runs. The stale sweeper returns the leases within one lease period, and
  deliberately does **not** increment `attempts`: a pod that died before dispatching made no attempt,
  and counting one would spend a retry on an infrastructure failure that has nothing to do with the
  recipient.
- **A replica losing a lock mid-job** — every job renews between units of work and stops immediately
  on a failed renewal, because continuing past it means two replicas are in the same job.

## PII discipline and the retention policy

The two sensitive things here are **recipient addresses** and **rendered bodies**.

- **Not filterable.** `recipient_address` carries no `@Filterable`, so no `$filter` can reach it however
  it is spelled. Filterable means enumerable, and an endpoint that answers *"does any delivery exist
  for this address?"* is an address-validity oracle. `recipient_user_id` is filterable **by admins
  only** — a pseudonymous subject that support genuinely needs.
- **Not in metric tags.** No metric takes a recipient, an address, a template variable or a delivery
  id. Each is unbounded; one tag valued by recipient turns a campaign to a million people into a
  million time series.
- **Not in problem details.** Provider exception messages quote the recipient back; they are never
  passed through to a response.
- **Masked in logs.** `Pii.address` produces `j***@e***.com`; `Pii.body` reduces a body to its length,
  because the first eighty characters of a one-time-code email frequently contain the code. This is
  the actual control — the observability module's key-based masking cannot see an interpolated message
  argument — and its masking is the backstop.
- **Not on the event topic.** The lifecycle events carry a delivery id, a request id and a pseudonymous
  subject, and no address or content. A topic with a long retention is outside this service's purge.
- **Rendered content lives in its own table**, so retention has a single cheap target and the hot
  delivery row stays narrow.

### Retention

Seven windows. The four delivery ones widen outwards; the two inbox ones are anchored on the item
having been **read or dismissed** rather than on its age, which is why they are months where
`content-ttl` is days. The relationships are checked at startup: a body kept longer than the thing
that owns it means the purge orphans it, and an inbox window shorter than `content-ttl` would make
the inbox expire before the push attempt it exists to outlive.

| What | Property | Default | Why this length |
|---|---|---|---|
| Rendered bodies | `retention.content-ttl` | 7d | the most sensitive thing here; as long as a customer might ask what we sent |
| Addresses and variable maps | `retention.recipient-data-ttl` | 7d | scrubbed in place, leaving the delivery row intact |
| Delivery rows | `retention.delivery-ttl` | 90d | by then carrying no personal data; capacity planning and bounce rates |
| Status history | `retention.history-ttl` | 180d | the audit trail, personal-data-free by construction |
| Inbox items + bodies | `retention.inbox-ttl`, `inbox-content-ttl` | 90d | **anchored on being read**, not on age — see [the inbox](#the-inbox-the-one-passive-channel) |
| Unread inbox items | `retention.inbox-unread-max-age` | **unset** | discarding one loses something its recipient never saw, so it is opt-in and logged at `WARN` |
| Announcements + content, markers, email run | `retention.announcement-ttl` | 90d | anchored on **`visible_until`** — everything cascades from one row, so the purge is a constant number of statements whatever the audience was |

Also purged: expired suppressions (**never** permanent ones — a spam complaint does not stop being true)
and closed rate-limit windows. **Expired dedup claims are not in this list any more**: that table belongs
to `idempotency-spring-boot-starter`, which runs its own batched purge under the same `RunLock` mechanism.
Two purges of one table on two schedules is exactly the duplication the promotion removed.

## Metrics, and the two that matter

`NotificationMetrics` with a `Noop` fallback always registered, matching every other module here. The
fallback is what lets every call site record unconditionally: a nullable collaborator puts an `if` in
front of every recording, and the one that gets forgotten is an NPE in the dispatch loop.

**The two worth alerting on:**

| Metric | Read it as |
|---|---|
| `notification.queue.depth{channel,priority}` | how much is waiting |
| `notification.queue.oldest.pending.age` | **whether anything is moving** |

Depth alone is a number without a scale — fifty rows is nothing during a campaign and alarming at
three in the morning. An oldest-pending age above threshold means the queue is not moving, at any
volume. Every lane publishes a zero when empty, so a drained queue is distinguishable from an exporter
that has died.

Also: `notification.requests{source,outcome}`, `notification.deliveries{channel,priority,outcome,reason}`,
`notification.sends{channel,outcome,class}`, `notification.receipts`, `notification.queue.claims`,
`notification.queue.reclaimed` (non-zero means a pod was killed), `notification.queue.rate.limited`,
and timers for send, render and end-to-end latency.

The lock meters are `job-core`'s, not this service's: `ludwig.job.lock.acquisition{lock,acquired}` and
`ludwig.job.lock.lost{lock}`. A second counter recorded from this side would be a second answer to the
same question, and the two would disagree the first time a lock was taken by anything other than a
notification job.

Template key is deliberately **not** a tag: it is bounded in principle by a directory somebody can add
files to without touching this code, which is not a bound at all.

## Operational runbook

### The queue is backing up

`notification.queue.oldest.pending.age` is rising and readiness has gone red on some replicas.
Readiness — not liveness, deliberately: a backed-up queue describes a service that is perfectly alive,
and restarting it cannot fix a provider that is down.

1. **Which channel?** `notification.queue.depth` by channel. One channel means a provider; all of them
   means the database or the pollers.
2. **Are the pollers running?** `notification.queue.claims` per channel. Zero with a non-empty queue
   means either the poller is off (`ludwig.notification.queue.poller-enabled`), the channel is off
   (check the runtime ConfigMap), or the rate limit is exhausted (`notification.queue.rate.limited`).
3. **Is the provider failing or slow?** `notification.sends{outcome}` and
   `notification.send.duration`. A rising `RETRYABLE` count with growing latency is a provider in
   trouble; a rising `TERMINAL` count is a bad recipient list or a broken credential.
4. **Is a pod dying?** `notification.queue.reclaimed` non-zero means leases are being recovered, i.e.
   something is being killed mid-cycle. Check for OOM kills; the JVM is set to exit on
   `OutOfMemoryError` precisely so the sweeper can recover rather than the pod limping.
5. **Mitigate.** If one provider is the problem, switch that channel off in the runtime ConfigMap —
   queued deliveries wait rather than fail, and nothing is dead-lettered. If throughput is the
   problem, raise `channels.<x>.max-per-window` (cluster-wide, so the number means what it says) or
   `queue.batch-size` — **but raising the batch size means raising `queue.lease-timeout` too**, and
   startup validation will refuse the combination if you forget.

### Deliveries are being sent twice

The most serious failure this service can have. Check in this order:

1. `queue.lease-timeout` vs `queue.batch-size` × the slowest channel timeout. Startup validation
   refuses an unsafe combination, so this should be impossible — but verify it was not overridden.
2. `notification.queue.reclaimed` correlated with the duplicates: the sweeper reclaiming rows a healthy
   pod is still sending is the classic cause.
3. `ludwig.notification.idempotency.enabled` — with it off, every at-least-once redelivery is a new
   request. Then `ludwig.idempotency.ttl` (and any per-scope override): a window shorter than the longest
   redelivery a broker or a caller will perform converts duplicates into double sends, which is what this
   symptom looks like. Check `ludwig.idempotency.fingerprint.mismatches` too — a client reusing one key for
   different requests produces 422s nobody reports.
4. The unique index `ux_notification_delivery_dedup_key` — if it is missing, the last line of defence
   is gone.

### A digest or the purge has not run

Check `ludwig.job.lock.acquisition{lock,acquired}`. All three replicas reporting `acquired=false` and
none `acquired=true` means a lease is held by a pod that is gone. Inspect it —
`SELECT * FROM job_run_lock WHERE lock_name = 'notification-digest'` — and either wait out
`expires_at` (`ludwig.job-core.lock.default-lease`, two minutes at the shipped configuration) or clear
the owner: `UPDATE job_run_lock SET owner = NULL, run_id = NULL, expires_at = now() WHERE lock_name = …`.
Do **not** delete the row — a released lease keeps its row by design, and the next tick reuses it.

If instead `ludwig.job.lock.lost` is climbing, the opposite is happening: runs are being superseded
mid-flight, so the lease is too short for the work. Raise `ludwig.job-core.lock.default-lease` — the
startup validator refuses a lease longer than the schedule it guards, which is the ceiling.

### A template change has not taken effect

The mounted ConfigMap propagates to the pod filesystem on the kubelet's sync period (up to ~60s), then
the watcher invalidates the cache. Confirm with the preview endpoint, which renders through exactly
the same resolver: if the preview shows the new wording, the sends will too.

### Replaying dead letters

`GET /api/v1/notifications/deliveries?$filter=status eq 'DEAD'&$orderby=createdAt desc`, then
`POST /deliveries/{id}/retry` for each. The attempt counter is reset, because a retry follows a fix to
the cause and making it fight for its last attempt would let one further blip dead-letter it again.
Fix the cause first: `lastError` on the delivery says what happened, and
`GET /deliveries/{id}/content` shows exactly what was rendered.

## The published API document

This service already served its OpenAPI document at `/v3/api-docs` and a Swagger UI at
`/swagger-ui.html`. What is new is that the document is **committed**, at
`docs/api/notification-service.openapi.yaml`, so a change to this service's HTTP surface shows up as
a diff rather than only inside a running process.

`ApiDocumentIT` regenerates it at `verify` and fails the build when the committed copy no longer
matches what the service serves. An ordinary build never rewrites it; refreshing is an explicit
`-Dludwig.apidocs.write=true`, so the diff is always something the author asked for.
`docs/api/README.md` has the command and explains why the document is canonicalized - every object's
members sorted, `servers` stripped - before it is compared.

One detail in that document is worth knowing, because it was nearly wrong. The `ProblemDetail`
schema is declared by hand in `OpenApiConfig`, since every error here is produced by `web-core`'s
advice rather than returned from a controller, and a document inferred from return types alone would
describe only the happy paths. But springdoc prunes a component nothing refers to, and for a while
this schema survived only because `BatchItemResult` happens to carry a `ProblemDetail` field -
meaning the whole error contract would have vanished from the document the day that unrelated field
was removed. It is now attached to every operation as its `default` response, which is also the
accurate status to use: `web-core`'s single advice can answer on any operation, so listing 400, 404
and 409 per endpoint would be a list that drifts from the mappers.

## Configuration

Everything under `ludwig.notification.*`, one `@Validated @ConfigurationProperties` tree, with
`spring-boot-configuration-processor` on the path so an IDE completes it. There is no magic number
anywhere below that class.

`@Validated` catches a bad *value*. What it cannot catch is a relationship between two settings that
are individually valid and jointly wrong, and those are the ones that produce intermittent behaviour
nobody can attribute weeks later — so `NotificationConfigurationValidator` refuses to start on:

- a lease shorter than 2× the worst-case cycle (`batch-size` × slowest channel timeout) — **would
  double-send**;
- a lease no longer than the poll interval;
- a high-priority reserve ≥ the batch size — would stop `NORMAL` and `BULK` traffic entirely;
- a lock heartbeat not at least 3× shorter than the lease;
- an enabled webhook channel or receipt endpoint with no signing secret;
- an enabled chat channel with no base URL;
- a non-positive digest window;
- retention windows that do not widen outwards;
- `preferences.source: USER_SETTINGS` with no preference store actually wired in — the check that
  turns a silent fallback to configured defaults into a refusal to start.

Every problem is reported at once rather than one per deploy.

Two settings are deliberately **not** declared in `application.yml` —
`ludwig.hotreload.files[0].path` and `ludwig.hotreload.freemarker.template-directory`. Both activate
on the property merely existing, so a `${VAR:}` placeholder would switch them on against a directory
that is not there. The deployment sets them as environment variables; see `deploy/k8s`.

## Deviations and known gaps

Stated plainly rather than buried.

0. **This deployment runs without a broker and without a preference store**, and both are stated in
   configuration rather than worked around in code. The REST ingress carries the traffic a topic
   would have; per-recipient opt-outs, quiet windows and digest cadence read as unset. Neither is a
   fork of the design: the seams (`RecipientPreferenceSource`, `ingress.kafka-enabled`) are the same
   ones the brokered topology uses, and the code behind them ships and is tested either way. See
   [Running without a broker](#running-without-a-broker) and
   [Where preferences come from](#where-preferences-come-from).

   The honest cost, said once: **a recipient who opted out of marketing has no way to express it
   here today.** A `MARKETING` category still honours platform-wide quiet hours and the suppression
   list, but the individual opt-out is a statement that has nowhere to be stored, and this service
   deliberately does not keep a second copy of it. Deployments sending marketing before the account
   service publishes its settings should either not send it, or set
   `ludwig.notification.preferences.source: USER_SETTINGS` so the pod refuses to start until the
   store is there.

1. **Contact data belongs to the OIDC provider, and reaches this service through the identity
   projection.** This was decided explicitly rather than left ambiguous, because a half-owned address
   is the specific failure that produces two stores nobody can reconcile.

   The provider verifies a person's email and phone, so it is the single source of truth for them.
   `identity-projection-spring-boot-starter` carries them on `security_user` — but only where a
   deployment asks for it (`ludwig.identity.contact.enabled`), and this service is the only one in the
   estate that does. That answers the objection the projection's own documentation raises, which was
   right about the general case: directory data replicated into *every* service means every extra
   field is another copy of personal data in another place a deletion request has to reach. Gating it
   means the estate-wide copy never happens and there is still exactly one place an address is
   correct.

   An **unverified** address is treated as no address at all
   (`ludwig.notification.recipients.require-verified-contact`, on by default). A provider that says
   nothing at all is not treated as a refusal — that would have stopped every message on the day the
   field was introduced.

   A user unknown to the projection is **not** a failure: the notification still goes out for a
   literal address, because the projection is fed by a Kafka stream and a new user can be addressable
   before their record arrives. A user with no usable address is a **terminal delivery**, not a
   rejected request — a request naming five people where one is unreachable produces four sends and
   one explicable failure.

   What genuinely went away is the per-user **webhook URL**. A webhook is a machine destination rather
   than a person's contact point, and a request that wants one names it literally as an `ADDRESS`
   recipient — which it could always do.

2. **`BATCHED` and `COLLAPSED` were added to the specified lifecycle.** Without them, collapsing is
   unrepresentable. See [the state machine](#the-delivery-state-machine).

3. **Rate limits are cluster-wide by construction**, not per replica. See
   [Priority lanes and rate limits](#priority-lanes-and-rate-limits).

4. **The receipt endpoint has two models, not three.** Its payload deserializes straight into
   `DeliveryReceipt`. A web DTO would have to live in the web layer, and a service importing it would
   be reaching into an entry point — a worse coupling than the extra layer buys. The signature covers
   the exact bytes received, so nothing may deserialize them before verification; the published schema
   is in the controller's OpenAPI description.

5. **Two packages hold configuration**, and the architecture rules were widened rather than switched
   off (`conventions.packages.configuration.add = ..settings..`). `config` is the composition root and
   depends on the service; `settings` holds the typed properties that every layer reads and therefore
   must depend on nothing. Together in one package they would form a cycle no rearranging could remove.
   No architecture rule is disabled except `storage`, which this service genuinely has none of.

6. **Three bugs were fixed in shared modules** to make this service work; all three affected any
   service using those starters:
   - `web-core` and `security` both registered a bean named `ludwigSecurityProblemMapper`, so an
     application with both failed to start. Renamed in `web-core`; the two are *meant* to coexist and
     resolve by `Ordered`.
   - `security`'s `ResourceServerAutoConfiguration` was declared `@AutoConfiguration(after = …)`, so
     its `SecurityFilterChain` lost the `@ConditionalOnDefaultWebSecurity` race **every time** and
     services silently ran on Boot's default chain — no public paths, CSRF on, default empty-bodied
     401/403, no mTLS filter. Changed to `before`.
   - `observability`'s histogram `MeterFilter` matched `http.server.requests` by *prefix*, so it also
     caught the `…​.active` long-task timer and forced an invalid distribution range onto it —
     throwing on the first HTTP request the service ever served. Changed to an exact match.

7. **The in-app channel is a carve-out from "a transport is a bean", with a boundary and two tests.**
   Stated here as well as in [its own section](#the-inbox-the-one-passive-channel), because the
   add-a-channel instructions are what a reader finds first and they are no longer the whole story.
   The honest cost: the dispatch path is no longer identical for every transport. The reason it is
   worth paying is that the queue protects against a hazard this destination does not have, and
   deferring a row insert out of the transaction already writing it is strictly less reliable.

8. **Real-time push is deliberately absent.** No SSE, no websocket. An unread count polled every
   thirty seconds is honest and free; push is decoration over a durable inbox and must never become
   the delivery mechanism. No rule forbids it today, on purpose - a rule with no code to fire on
   passes forever and reads as coverage.

9. **The fallback does not fire on an unreachable channel**, only on a refused one. See
   [the fallback](#the-fallback).

10. **Announcement audiences are `EVERYONE` and `ROLE` only.** Organisation units are deliberately
    absent: the identity provider owns the org structure (`ou_id`, `organization`) and
    `identity-projection` does not carry it yet. Adding it is a separate change that touches a shared
    starter; the audience model has the hole shaped for it — one more kind, one more `OR` branch.

11. **There are no notification-local user groups, and there will not be.** A durable, meaningful
    grouping of people is directory data and belongs to the provider; a one-off "tell these forty
    people" is already `recipients: [...]` on an ordinary request. The case in between — a reusable
    list that exists only here — is the one that rots, because no leaver process touches it and it
    quietly announces things to people who left eighteen months ago.

12. **An announcement cannot be acknowledged**, only dismissed. "This must stay until acknowledged,
    and report who has not" is a different feature with its own reporting surface.

13. **A published announcement's audience is immutable.** Its content can be corrected; moving it
    between audiences cannot, because part of the audience may already have been emailed and the
    change would make the record of who was told untrue.

14. **The emailed audience and live visibility can diverge.** Somebody who loses the targeted role
    while a fan-out is still walking may or may not be emailed. Specified and tested rather than
    hidden - a run lasting minutes cannot also be instantaneous.

15. **Not implemented:** provider-specific receipt adapters (the endpoint takes this service's own
   normalised shape, so each provider needs a small translation in front of it), and per-tenant
   template overrides. Digest is implemented but ships **disabled**, because a digest window is a
   product decision per category rather than something to default.

---

## Build and test

```bash
mvn -pl notification-service -am clean install     # requires Docker for the integration tests
mvn -pl notification-service test -Dtest='*Test'   # unit + integration + architecture
docker build -f services/notification-service/Dockerfile -t notification-service:1.0.0 .   # context = repo root
```

458 tests (286 unit, 172 integration): unit coverage of rendering strictness, backoff and jitter,
opt-out precedence in all three states, the three-by-two category-class bypass matrix, both preference
sources and the degradation between them, batch isolation, quiet hours across midnight and timezones,
failure classification per channel, PII masking, the channel classification and its setting-key
mapping, the inbox's monotonic transitions, the announcement audience allowlist and its
indistinguishable refusals, what an announcement's audit record must not contain, and message-bundle
parity; Testcontainers
integration coverage of the full path ingress → queue → dispatch against real PostgreSQL and a real
SMTP server (GreenMail), the in-app settlement and that the claim query never sees it, the inbox
schema's foreign-key delete actions and partial retention index, the recipient-facing inbox API and
its three scoping defences, read-anchored retention, the suppression fallback's four conditions, the
announcement publishing with per-locale rendering and its atomicity, the audience check constraints
and cascade directions, role-based visibility with grant and revocation taking effect on the next
read, the two feeds not leaking into each other, the resumable email fan-out with its
exactly-once-on-resume and cooperative cancellation, announcement retention cascading from one row,
the REST batch and read-back endpoints and their scoping, concurrent claim
disjointness at three simulated replicas, stale reclaim, idempotent redelivery over a real Kafka
broker, retry to `DEAD`, suppression before and after enqueueing, receipts, data scoping, and the 47
architecture rules.

The user-settings adapter is covered although the module is excluded from the packaged application —
that is what the `provided` scope buys: the types are on the test classpath and out of what runs, so
the path that will be switched on later does not rot in the meantime.
