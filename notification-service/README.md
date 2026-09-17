# notification-service

***English** · [Русский](README.ru.md)*

The platform's notification service: every other service asks this one to notify somebody instead of
talking to a mail server itself.

Two ingresses converge on one application service — a Kafka consumer for fire-and-forget traffic and
a REST API for synchronous and operator-initiated sends. A request fans out into one delivery per
recipient per channel, held in a Postgres work queue; a poller claims due deliveries with
`FOR UPDATE SKIP LOCKED`, renders a FreeMarker template in the recipient's language, and hands the
result to a channel — SMTP, the internal chat API, or a signed webhook.

It is built from this repository's own modules and adds the two things none of them provide yet:
consumer-side idempotency and a distributed lock.

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
- [Promotion candidates: the two platform gaps](#promotion-candidates-the-two-platform-gaps)
- [The request contract](#the-request-contract)
- [The Kafka topic](#the-kafka-topic)
- [How to author a template](#how-to-author-a-template)
- [How to add a channel](#how-to-add-a-channel)
- [Preferences, quiet hours and suppression](#preferences-quiet-hours-and-suppression)
- [Priority lanes and rate limits](#priority-lanes-and-rate-limits)
- [Behaviour at three replicas](#behaviour-at-three-replicas)
- [PII discipline and the retention policy](#pii-discipline-and-the-retention-policy)
- [Metrics, and the two that matter](#metrics-and-the-two-that-matter)
- [Operational runbook](#operational-runbook)
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
                     └──→ DEAD       (unresolvable)         │           └──→ DEAD     │ + jitter
                                                            └─────────────────────────┘
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

## Promotion candidates: the two platform gaps

Both live behind a narrow interface in this service and both belong in a platform starter. **Without
either of them this service double-sends at more than one replica** — they are not optional polish.

### 1. `IdempotencyStore` — consumer-side idempotency

The Kafka consumer is at-least-once by construction and REST callers retry on timeouts that were often
successful writes, so *"have I already done this?"* is the normal path during any rebalance, deploy or
network blip.

The correctness is entirely in one statement:

```sql
INSERT INTO notification_idempotency (id, scope, idempotency_key, request_id, created_at, expires_at)
VALUES (…)
ON CONFLICT (scope, idempotency_key) DO UPDATE SET scope = notification_idempotency.scope
RETURNING request_id
```

Read-then-insert passes a single-threaded test and fails exactly here: two replicas handling the same
record both read "no row", both insert, and one takes a constraint violation that aborts a transaction
which has already written a request and its deliveries. `DO UPDATE` makes the loser block on the
winner's row lock and then read the committed winner — no exception, no rollback, no double send.
`DO NOTHING` would not do either: it returns no row on conflict, and in `READ COMMITTED` the re-select
can still miss a row whose inserting transaction has not committed.

Scoped by ingress, because a Kafka record key and an HTTP `Idempotency-Key` come from different
namespaces and a collision between them would silently drop a genuine request.

### 2. `DistributedLock` — a leased, cluster-wide mutex

The delivery poller **does not use it and must not**: `SKIP LOCKED` already partitions the queue, so
three pollers claim disjoint batches with no coordination. Putting a lock there would throw away two
thirds of the throughput to solve a problem that does not exist.

What needs it is every job defined over a *set* of rows rather than over each row independently: the
digest collapse (three replicas → three digests for one person), the retention purge, and the
suppression compaction.

A leased row rather than `pg_try_advisory_lock`. An advisory lock is held by the database *session*,
and with a connection pool the session returns to the pool the moment the statement finishes — so
holding one across a multi-minute digest run means pinning a pooled connection and trusting nothing in
the stack quietly returns it. It is also invisible: an operator asking "why has the digest not run for
an hour?" has nothing to look at. A row with an explicit `expires_at` is inspectable, survives the
connection, fails over on a configured timeout rather than an accidental one, and can be broken by
hand.

Both are small, self-contained and dependency-free. Lifting `0003-notification-platform-gaps.xml`
plus the two interfaces and their Postgres implementations into a starter would be a mechanical move.

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

Other endpoints: `POST /preview` (render without sending), `GET /deliveries` (OData search),
`GET /deliveries/{id}` + `/history` + `/content`, `POST /deliveries/{id}/retry|cancel`,
`PUT /recipients/{userId}` + `/preferences`, `GET|POST|DELETE /suppressions`, and
`POST /receipts` for provider callbacks. Full schemas at `/swagger-ui.html`.

## The Kafka topic

Default `platform.notifications.requests`, group `notification-service`, dead letters to
`platform.notifications.requests.dlt`.

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
`NotificationProperties`.

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

## Preferences, quiet hours and suppression

Three mechanisms, checked in this order, and the order matters.

1. **Suppression list** — a fact about the *destination*: a hard bounce, a spam complaint, an
   unsubscribe. It has **no bypass at all**, not even for a transactional notification, because
   continuing to send to such an address damages the sending domain's standing with the mailbox
   provider for every other recipient. Checked at fan-out *and* again immediately before dispatch,
   because a delivery can sit behind a backoff for hours and a recipient can unsubscribe in that
   window.
2. **Preferences** — per recipient, per category, per channel. Absence means allowed. Four rows can
   bear on one decision (exact pair, category on all channels, wildcard category on this channel,
   total wildcard) and the **most specific wins**, which is what lets somebody say *"nothing at all,
   except order updates by email"* as two rows without the record of their original request being
   deleted. A `TRANSACTIONAL` category bypasses this entirely.
3. **Quiet hours** — evaluated in the *recipient's* timezone, never the server's, and handling a window
   that wraps midnight (22:00→07:00), which is what people actually configure. A marketing
   notification arriving inside the window is **deferred** to the end of it, not dropped: the caller
   was told the request was accepted, so something has to arrive.

The transactional/marketing split is a property of the **category**, not a flag on the request. As a
request flag every calling service would set it, and every one would set it to true — from inside any
one service its own notification always looks important. As a category property the decision is made
once, by whoever owns the notification catalogue, and a service wanting its campaign exempted has to
argue for it.

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
| **Digest collapse** | **exactly one**, under `notification-digest` | defined over a *set* of rows; three replicas would send three digests to one person |
| **Retention purge** | **exactly one**, under `notification-retention` | idempotent, but three would contend on the largest table at the worst moment |
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

Four windows, widening outwards. The relationship is checked at startup, because keeping a body longer
than the delivery that owns it means the purge orphans it.

| What | Property | Default | Why this length |
|---|---|---|---|
| Rendered bodies | `retention.content-ttl` | 7d | the most sensitive thing here; as long as a customer might ask what we sent |
| Addresses and variable maps | `retention.recipient-data-ttl` | 7d | scrubbed in place, leaving the delivery row intact |
| Delivery rows | `retention.delivery-ttl` | 90d | by then carrying no personal data; capacity planning and bounce rates |
| Status history | `retention.history-ttl` | 180d | the audit trail, personal-data-free by construction |

Also purged: expired idempotency keys, expired suppressions (**never** permanent ones — a spam
complaint does not stop being true), and closed rate-limit windows.

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
`notification.locks{lock,outcome}`, and timers for send, render and end-to-end latency.

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
   request.
4. The unique index `ux_notification_delivery_dedup_key` — if it is missing, the last line of defence
   is gone.

### A digest or the purge has not run

Check `notification.locks{outcome}`. All three replicas reporting `contended` and none `acquired`
means a lock is held by a pod that is gone. Inspect it — `SELECT * FROM notification_lock` — and
either wait out `expires_at` (two minutes at the shipped configuration) or delete the row.

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
- retention windows that do not widen outwards.

Every problem is reported at once rather than one per deploy.

Two settings are deliberately **not** declared in `application.yml` —
`ludwig.hotreload.files[0].path` and `ludwig.hotreload.freemarker.template-directory`. Both activate
on the property merely existing, so a `${VAR:}` placeholder would switch them on against a directory
that is not there. The deployment sets them as environment variables; see `deploy/k8s`.

## Deviations and known gaps

Stated plainly rather than buried.

1. **The identity projection cannot supply contact details, and should not.**
   `identity-projection-spring-boot-starter` stores a subject, display name, tenant, status and roles —
   its own documentation gives the reason: it is directory data replicated into *every* service using
   the module, so each extra field is another copy of personal data. An email address, a chat handle
   and a home timezone are not authorization inputs.
   So the split is: the projection answers *does this user exist, are they active, what do we call
   them, whose tenant are they in*; `notification_recipient_profile` answers *where do we send it and
   when not to*. This service needs the second half anyway — preferences and quiet hours hang off it.
   A user unknown to the projection is **not** a failure: the notification still goes out, without the
   enrichment, because the projection is fed by a Kafka stream and a new user can be addressable
   before their record arrives.

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

7. **Not implemented:** provider-specific receipt adapters (the endpoint takes this service's own
   normalised shape, so each provider needs a small translation in front of it), and per-tenant
   template overrides. Digest is implemented but ships **disabled**, because a digest window is a
   product decision per category rather than something to default.

---

## Build and test

```bash
mvn -pl notification-service -am clean install     # requires Docker for the integration tests
mvn -pl notification-service test -Dtest='*Test'   # unit + integration + architecture
docker build -f notification-service/Dockerfile -t notification-service:1.0.0 .   # context = repo root
```

181 tests: unit coverage of rendering strictness, backoff and jitter, preference precedence, quiet
hours across midnight and timezones, failure classification per channel and PII masking;
Testcontainers integration coverage of the full path ingress → queue → dispatch against real
PostgreSQL and a real SMTP server (GreenMail), concurrent claim disjointness at three simulated
replicas, stale reclaim, idempotent redelivery over a real Kafka broker, retry to `DEAD`, suppression
before and after enqueueing, receipts, data scoping, and the 47 architecture rules.
