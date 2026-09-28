# idempotency-spring-boot-starter

**The platform's one work-dedup store**: claiming a caller-supplied key in a scope so the same ask cannot
be acted on twice. Promoted out of `notification-service`, where the primitive was written and where its
own README called it promotion candidate #1 — *"one of the two capabilities the platform does not have
yet… without it this service double-sends the moment it runs at more than one replica."*

The correctness is entirely in one statement, and everything else in this module is built on it:

```sql
INSERT INTO idempotency_claim AS t (…)
VALUES (…)
ON CONFLICT (scope, idempotency_key) DO UPDATE SET
    request_id = CASE WHEN <reclaimable> THEN EXCLUDED.request_id ELSE t.request_id END,
    …                                     -- one assignment of this shape per column
RETURNING request_id, state, fingerprint, lease_expires_at, expires_at, response_*
```

---

## Contents

- [What this is for, and the six things it is not](#what-this-is-for-and-the-six-things-it-is-not)
- [The statement, and why every alternative is wrong](#the-statement-and-why-every-alternative-is-wrong)
- [The two claim modes](#the-two-claim-modes)
- [The HTTP surface](#the-http-surface)
- [Request fingerprinting](#request-fingerprinting)
- [Consumer-side dedup](#consumer-side-dedup)
- [The retention purge, and why the TTL is a correctness parameter](#the-retention-purge-and-why-the-ttl-is-a-correctness-parameter)
- [Backends](#backends)
- [Observability](#observability)
- [Audit](#audit)
- [What this module deliberately does not absorb](#what-this-module-deliberately-does-not-absorb)
- [Module structure, and the tripwire](#module-structure-and-the-tripwire)
- [Configuration](#configuration)
- [Migrating from a local store](#migrating-from-a-local-store)
- [Tests](#tests)

---

## What this is for, and the six things it is not

This module answers exactly one question: **"have I already done this work?"**, about a key somebody handed
us. `IdempotencyStore` is deliberately narrow, and the boundary is the most important thing in this README —
because "idempotency" names seven different mechanisms in this repository, and only two of them belong here.

| # | mechanism | where it lives | here? |
|---|---|---|---|
| 1 | **claim a caller-supplied key in a scope** | **this module** | **yes — this is the primitive** |
| 2 | **send a key downstream so the far side can dedup** | `IdempotencyHeaders`, `crud-service-example` | **yes — as a documented helper** |
| 3 | dedup on publish — `OutboxMessageRepository.findByIdempotencyKey` | `outbox-spring-boot-starter` | no |
| 4 | key as a column on a business aggregate — `ExportReportRun.findByIdempotencyKey` | `export-spring-boot-starter` | no |
| 5 | natural-key dedup — the `(bucket, key, etag)` unique constraint | `file-ingest-spring-boot-starter` | no |
| 6 | RFC 9110 retry classification — `IdempotentMethods`, `HttpMethod.isIdempotent()` | `rest-client`, `jira-client` | no |
| 7 | last-write-wins on a version | `identity-projection`, `user-settings` | no |

Mechanisms 3–7 are **unchanged**, and see [What this module deliberately does not absorb](#what-this-module-deliberately-does-not-absorb)
for why each one is the correct local answer and would be made worse by a generic store.

## The statement, and why every alternative is wrong

The statement lives in `ru.ludwigandreas.idempotency.sql`, which is this module's one documented carve-out
from the repository's QueryDSL-only rule, fenced by `SqlConfinementTest`. Neither `ON CONFLICT` nor
`RETURNING` exists in JPQL, and QueryDSL-JPA generates JPQL.

The whole value is that it is **one** statement:

- **Read-then-insert** is the obvious implementation and it is exactly wrong for the case this exists to
  handle. Two replicas processing the same at-least-once record both read "no row", both insert, and one
  takes a constraint violation that aborts a transaction which has by then already written real work. It
  also fails *silently*: under low load it looks perfect.
- **`DO NOTHING`** returns no row on conflict, so the loser has to re-select — and in `READ COMMITTED` a
  re-select can still miss a row whose inserting transaction has not committed. The `DO UPDATE` makes the
  loser **block on the winner's row lock** and then read the committed winner. No exception, no rollback, no
  double execution.
- **The unique index is the enforcement**, not the checking code. Nothing in Java achieves what
  `uk_idempotency_claim_key` achieves.

One refinement over the statement as it was lifted: the conflict branch also **reclaims** a claim that is
free — its window has passed, its holder's lease has expired, or it was reported `FAILED`. Expiry is decided
in the statement rather than left to the purge, because the purge is asynchronous and runs on one replica on
a schedule: without this, the TTL would mean "the TTL, plus however long until the purge next ran". Every
column's conflict assignment has the same shape, which is what makes "a live claim is left untouched" true
of the whole row rather than of the columns somebody remembered.

## The two claim modes

One mode cannot serve both callers, and **choosing is a required decision at every call site** — there is no
default, because picking the wrong one is a correctness bug that only appears under concurrency.

| | `TRANSACTIONAL` | `STANDALONE` |
|---|---|---|
| transaction | the caller's (`MANDATORY`-equivalent) | its own (`REQUIRES_NEW`-equivalent) |
| lifecycle | commits with the work; a rollback frees the key | `IN_PROGRESS → COMPLETED \| FAILED`, committed independently, leased |
| a duplicate is told | "done, and here is the owning request's id" | "done, here is the response" or "in flight, come back" |
| a dead holder | cannot happen — the claim was never committed | its lease expires and the key becomes claimable |
| for | a consumer whose whole unit of work is one transaction | the HTTP filter, and any work spanning transactions or calling out |

`TRANSACTIONAL` refuses rather than degrades when no transaction is open. Degrading to an independent commit
is the failure the mode exists to prevent, and it would only surface as a key that cannot be retried after
some unrelated rollback, days later.

`STANDALONE` needs a lease for the same reason `RunLock` does: a process that dies mid-request would leave
`IN_PROGRESS` forever, and **a key stuck in progress is a caller who can never retry** — which is worse than
the double execution it was preventing, because it is permanent. A `FAILED` claim is immediately
reclaimable: a failed attempt must not block the retry it exists to enable.

The two modes are two transaction boundaries expressed with a `TransactionTemplate` rather than
`@Transactional`, because the mode is a runtime value and `@Transactional` is applied by a proxy around
calls arriving from *outside* the bean. A `claim` method dispatching to an annotated `claimTransactionally`
on `this` would bypass the proxy silently, and only under concurrency. The two ways out of that trap are a
second bean whose only purpose is to be somebody else — which is what `notification-service`'s
`LockLeaseService` was, and what `job-core` deleted — or declaring the boundary where the decision is made.

## The HTTP surface

Off by default (`ludwig.idempotency.http.enabled`). A filter that started deduplicating every matching
endpoint the moment this module appeared on a classpath would change the behaviour of live APIs as a side
effect of a dependency bump.

Opt an endpoint in with `@Idempotent` on the handler, or with a path-and-method matcher in configuration for
endpoints a service does not own. Four paths:

| the key is | answer |
|---|---|
| **fresh** | the handler runs, and its response is stored on the claim |
| **completed** | the stored response is **replayed** and nothing runs |
| **in flight** | `409` with `Retry-After` — not a response that does not exist yet, and not a second execution |
| a **fingerprint mismatch** | `422` naming the key, and **never** the first request's response |

A **failed** claim is not a fifth path: the statement reclaims it, so the retry of a failed request arrives
as a fresh key.

**Response replay is the part a duplicate flag cannot do**, and it is what makes the feature real for an
API. A caller who retried a timed-out `POST` needs the original `201` and its body — what was created, where
it is, what its id was — not a boolean saying "you already did this".

Two things the filter refuses to store, because replaying them would be worse than replaying nothing:

- a **truncated** body (past `max-stored-response`), which would be a valid-looking response missing its end;
- a **4xx or 5xx**, which is also treated as *the work did not happen*: the claim is marked `FAILED` and the
  key freed. A stored 5xx would make a transient failure permanent for that key, and a stored 4xx would
  refuse the retry of a request the client **corrected** as a fingerprint mismatch rather than letting it
  succeed.

A 2xx whose response was too large to keep completes the claim — the work happened and must not repeat — and
a later duplicate gets `409 ludwig.idempotency.error.not-replayable`, with no `Retry-After`. That is a
separate code from the in-flight 409 on purpose: the two need opposite things from the caller, and a
`Retry-After` there would produce a polite infinite loop.

The 409 and the 422 are RFC 9457 problem documents rendered through **`web-core`'s own**
`ProblemDetailFactory` and message bundles. This module ships no `@RestControllerAdvice`; a filter is outside
Spring's per-handler exception handling, so it runs the same two steps the shared advice runs, against the
same beans, producing the same document shape — in the caller's language, as UTF-8.

## Request fingerprinting

**A correctness requirement, not a nicety.** Without it, a client that recycles keys silently receives the
wrong resource's response: a data-integrity failure that presents as "the API returned somebody else's data",
with a clean access log, a 200, and no error anywhere.

- A **hash**, not the body. Cheaper, and it avoids a table that accumulates request payloads — which would
  need `audit-core`'s redaction rules applied to it and an entitlement check on every reader.
- A JSON body is **canonicalised** (object keys sorted, whitespace dropped, `charset`/`boundary` parameters
  ignored) before hashing. Hashing raw bytes would call a retry that re-serialised its body a mismatch and
  refuse it with a 422 — turning this feature from a safety net into an outage.
- **Array order is preserved.** A JSON array is ordered by specification, and this module is in no position
  to decide otherwise for somebody else's payload. Guessing wrong here means treating a genuinely different
  request as a duplicate, which is the one failure worse than refusing a retry.
- A claim with **no** stored fingerprint never mismatches, so a release before fingerprinting was switched on
  does not start refusing the retries it was taken to protect.

## Consumer-side dedup

`IdempotentRecordFilterStrategy` claims `topic:partition:offset` — or a configured header — before the
listener body runs, in `TRANSACTIONAL` mode.

A `RecordFilterStrategy` rather than an aspect, and the second reason is the load-bearing one: an aspect
would drag `spring-boot-starter-aop` into every consumer, and a filter strategy runs **inside the listener
container's invocation**, which is inside the container's transaction when one is configured. A claim
committed outside the listener's transaction would leave the key reserved for work that rolled back, and the
redelivery Kafka will certainly perform — the offset was not committed either — would be discarded as a
duplicate. **The message would never be delivered and nothing would say so.**

```java
factory.setRecordFilterStrategy(idempotentRecordFilterStrategy);
factory.getContainerProperties().setTransactionManager(transactionManager);
```

It does not attach itself to every listener, deliberately. Some listeners must **not** be deduplicated: a
projection that converges on a value — the identity projection's timestamp compare, the user-settings replay
— is already correct on a replay, and a claim in front of it would add a table, a write and a failure mode
for a guarantee it already had.

The default key is the record's *coordinates*, which identify a **delivery** rather than a message — the
right default for at-least-once redelivery. Use `key-header` when a producer republishes one logical event to
a new offset; only the producer knows that two records are one event. Never the record's message key, which
identifies a partition's worth of related records and would dedup every event about one entity down to the
first.

## The retention purge, and why the TTL is a correctness parameter

`IdempotencyStore.purgeExpired`'s original javadoc already said the important half: *"must run on exactly one
replica — it is one of the jobs the distributed lock exists for."* At the time the lock was also in
`notification-service`; it is `job-core`'s now, so this module ships the purge rather than leaving it to every
consumer — a module that owns a TTL has to own the thing that makes the TTL true.

`SelfSchedulingLifecycle` + `RunLock`, modelled on `ExportRetentionPurge`, **batched, with the lease renewed
between batches**. The first run after somebody shortens the TTL may have millions of rows to drop, and one
statement over all of them would pin the oldest transaction id, stop autovacuum on a table receiving an
insert per protected request, and hold locks across its hot path. A lost lease stops the run; the batches are
independent, so the rest go on the next tick.

**The TTL is the window in which a retry is recognised.** Shortening it converts duplicates into double
executions, not into disk savings. It is defaulted generously (24 hours, the conventional floor for an HTTP
`Idempotency-Key`) and is settable per scope, because a Kafka consumer usually needs **longer** than its
topic's retention while an HTTP caller rarely needs more than a day.

If the purge cannot keep up it says so at `warn`, naming the knobs — a bigger batch or a shorter interval,
never a shorter TTL.

## Backends

| | Postgres (default) | Redis |
|---|---|---|
| `TRANSACTIONAL` | yes | **no** |
| `STANDALONE` | yes | yes |
| purge | this module's job | Redis expires keys itself |
| reclaim of an abandoned claim | mutually exclusive (row lock) | best-effort (last writer wins) |

**A Redis claim is not transactional with the database write it protects.** That is not a limitation of the
implementation, it is what Redis is: the claim commits in a different system from the work, so no arrangement
of the two calls makes them one atomic act. It therefore serves `STANDALONE` only — and throws rather than
degrading, because substituting a standalone claim for a transactional one leaves keys claimed for work that
rolled back. A crash between the claim and the commit leaves a key claimed for work that never happened,
bounded by the lease rather than by the TTL.

Acceptable for an HTTP filter in front of a handler that is itself idempotent. **Not acceptable for the
consumer case**, which is the case the primitive was written for. That caveat is on
`RedisIdempotencyStore`'s class javadoc as well as here, because a team reaching for Redis is doing so for
throughput at the moment they are editing configuration, and this page is not what they are reading.

## Observability

| meter | tags | means |
|---|---|---|
| `ludwig.idempotency.claims` | `scope`, `outcome` | a claim was attempted |
| `ludwig.idempotency.replays` | `scope` | a stored response was replayed instead of re-running |
| `ludwig.idempotency.claims.failed` | `scope` | a claim was freed for the retry |
| `ludwig.idempotency.fingerprint.mismatches` | `scope` | **a client is reusing one key for different requests** |
| `ludwig.idempotency.purged` | — | claims dropped past their window |

**The mismatch counter is the one worth alerting on.** Every other meter here describes traffic — duplicates
and replays are the healthy consequence of retries, and a deployment with none of them is more likely to have
the feature switched off than to have perfect clients. A mismatch means a client is broken, and from the
client's side it looks like an occasional 422, which nobody will report.

`scope` is the only tag, drawn from a set fixed at deploy time. The key, the request id and the caller are
deliberately never tags: all three are unbounded, and any one would turn a metrics backend into a
per-request time series store. The interesting question about a duplicate is usually *who sent it*, and that
belongs to the audit trail.

## Audit

Two events, through `audit-core`'s one `AuditSink`, under the `idempotency` category:

- a **replay**, because a caller received a response this service did not produce for that call. When somebody
  later asks why the log shows one order and the client shows two confirmations, this row is the answer;
- a **fingerprint mismatch**, recorded as `DENIED` rather than `FAILURE` — the service refused the request on
  purpose and refused it correctly, and "we refused them" and "we broke" lead to opposite investigations.

Not one row per successful claim: that would be one row per request on every protected endpoint, burying the
two events that matter under traffic the access log already has. Neither event carries the request body or the
fingerprints — publishing a fingerprint would let a reader of the trail confirm a guess about a request's
contents.

`IdempotencyAuditEvent` is the typed authoring surface with a `toAuditEvent()`, exactly as `IngestAuditEvent`
and `ExportAuditEvent` are. This module declares no audit SPI of its own, no logger named `*.audit` and no
redaction mask.

## What this module deliberately does not absorb

Four of the five mechanisms below are the **correct local answer**, and a generic store would make each one
worse: an extra table, an extra write in the hot path, and a second thing that can fail. This section exists
so that the next person reading the list does not "finish the job".

**3. Dedup on publish** — `outbox-spring-boot-starter`'s `findByIdempotencyKey` and
`OutboxProperties.Idempotency`. A lookup by unique column on the outbox's own row, where **returning the
existing message *is* the contract**. Nothing to claim and nowhere to claim it.

**4. A key as a column on a business aggregate** — `export-spring-boot-starter`'s
`ExportReportRun.findByIdempotencyKey`. The key belongs to the run. A second table would add a write on the
run-creation path and a row that can outlive or predate the run it describes, for a guarantee the run's own
unique column already gives.

**5. Natural-key dedup** — `file-ingest-spring-boot-starter`'s unique constraint on `(bucket, key, etag)`.
**Nobody supplies a key**; identity is derived from the data, which is strictly stronger than trusting a
caller to send the same string twice. It is also why a *corrected re-upload* is correctly not skipped — a
caller-supplied key would have hidden that.

**6. RFC 9110 retry classification** — `rest-client`'s `IdempotentMethods` and `jira-client`'s
`HttpMethod.isIdempotent()`. A different question with a different answer: *"may I retry this method"*, not
*"have I done this work"*. A `GET` is idempotent and has no work to dedup; a `POST` carrying one of these keys
is retryable precisely **because** it carries one. The two mechanisms meet at
`IdempotencyHeaders.KEY`, and that is the whole of their relationship.

**7. Last-write-wins on a version** — `identity-projection`'s timestamp compare, `user-settings`' replay.
Value-based convergence needs no key and no store. Putting a claim in front of it would add machinery to a
listener that is already correct on a replay.

### One small duplication that is out of scope here

`IdempotentMethods` (rest-client) and `HttpMethod.isIdempotent()` (jira-client) are the same RFC 9110 table
written twice. That is a genuine, much smaller task — one enum or one static table in a shared place — and it
belongs to **retry policy**, not to work-dedup. It was deliberately not folded into this module: mechanism 6
is on the "not here" list above, and absorbing it would make this module's boundary the very thing this
README spends a section defending.

## Module structure, and the tripwire

**One module is correct here**, unlike `audit-core`, which had to split. The check: nothing upstream of
`db-core` needs the store. The lowest-level consumers of work-dedup are services and the outbox-facing
helpers, all of which already sit above `db-core`, `job-core` and `web-core` — so this module depends on all
three directly. `mvn -pl db-core -am verify` builds only `web-core` and `db-core`, which is the proof.

**The tripwire**, stated in the POM as well as here: if `rest-client-*` or `hot-reload-*` ever needs the
store, this **must** be split into `idempotency-core` (the SPI, the key, the fingerprint, with zero in-repo
dependencies) and the starter. Maven's reactor DAG ignores scope, so the cycle would otherwise appear at the
worst possible moment — as a reactor failure during somebody else's unrelated change. `SqlConfinementTest`
asserts the absence of those dependencies today.

## Configuration

```yaml
ludwig:
  idempotency:
    enabled: true
    backend: postgres            # or redis - read RedisIdempotencyStore's javadoc first
    ttl: 24h                     # a CORRECTNESS parameter: the window a retry is recognised in
    lease: 60s                   # how long an in-progress standalone claim survives without a renewal
    scopes:                      # per-scope overrides, for ingresses whose windows genuinely differ
      - scope: kafka:platform.orders
        ttl: 7d
    http:
      enabled: false             # off by default - see "The HTTP surface"
      path-patterns: []          # empty means "only @Idempotent handlers", which is the better way in
      methods: [POST, PATCH]     # never PUT/DELETE (idempotent by RFC 9110) and never GET
      key-required: false
      scope-per-endpoint: true
      fingerprint-requests: true
      max-fingerprinted-body: 262144
      max-stored-response: 262144
      replayed-headers: [Location, ETag, Content-Language]
      min-stored-status: 200
      max-stored-status: 299
    kafka:
      enabled: false             # registering the bean is not the same as switching dedup on
      mode: TRANSACTIONAL
      key-header:                # unset: the record's coordinates
      fingerprint-records: true
    purge:
      enabled: true
      interval: 15m
      initial-delay: 5m
      batch-size: 1000
      max-batches-per-run: 100
      lock-name: ludwig-idempotency-purge
    redis:
      key-prefix: ludwig:idempotency
    liquibase:
      enabled: true              # false if the consumer includes this changelog in its own master
```

`methods` is `POST` and `PATCH` and deliberately not `PUT` or `DELETE`: those are idempotent by RFC 9110
already, so a retry of one is safe without a key, and a stored replay would hide a genuine state change from
the second caller.

`replayed-headers` is an allow-list rather than "everything except". Replaying every header means replaying
`Date`, whatever a proxy added, and — at worst — `Set-Cookie`, which would hand a second caller the first
caller's session.

## Migrating from a local store

The changelog carries `idempotency-002-migrate-notification-idempotency`, which moves
`notification_idempotency` into `idempotency_claim`. It is `runAlways="true"` with a `tableExists`
precondition and two anti-joins, which is the shape `audit-002`/`audit-003` established for the same reason:
ordering between two `SpringLiquibase` beans is not the order their autoconfigurations are registered in, and
a changeset marked as ran is never reconsidered.

**Why the rows move rather than the table starting empty:** every key in the old table is a claim on work
that has been done. An empty table means every in-flight key is a duplicate the service will act on a second
time — once, during the deploy that migrates it. For `notification-service` that is a second email or a
second chat message to a real person, arriving as a side effect of introducing the module whose purpose is to
prevent it.

The mapping, and what is deliberately absent: `state` becomes `COMPLETED` (which is what every row in that
table meant — it had no state column because its only mode was the transactional one); `fingerprint`,
`lease_expires_at` and the response columns stay **null**, because a fabricated fingerprint would refuse the
very retries these rows exist to recognise; `created_at` and `expires_at` carry over, so a migrated claim
expires exactly when it would have rather than being silently extended by a whole TTL.

The **second** anti-join is the one that matters on a live deployment: between one boot and the next a request
may claim the same `(scope, key)` through the new table, and that claim is newer and authoritative. Inserting
the old row would violate the unique constraint and fail the whole migration, taking the application's startup
with it.

A consumer that includes this changelog in its own master changelog — which `notification-service` does —
gets deterministic ordering and can drop the legacy table in the same deploy. See
`0006-fold-idempotency-into-starter.xml` there, whose precondition is the reconciliation an operator would
otherwise perform by eye.

## Tests

Integration, against a real PostgreSQL, because everything load-bearing here is how Postgres behaves when two
transactions meet on one row — and because **a single-threaded test proves nothing**: read-then-insert passes
one.

| suite | pins |
|---|---|
| `IdempotencyStoreIT` | N threads on one key → one winner and the winner's id returned to every loser; `TRANSACTIONAL` rollback frees the key and the retry succeeds; `TRANSACTIONAL` with no transaction is refused; `IN_PROGRESS`/`COMPLETED`/`FAILED` duplicates; a dead holder's lease expires; a lapsed lease cannot renew; the TTL boundary at ±1 second; scopes are namespaces; the purge is selective |
| `IdempotencyHttpIT` | a retried `POST` replays the original `201`, its body and its `Location`, **and the handler runs once**; a re-serialised retry is still a retry; a reused key is `422` and the first response is *not* returned; an in-flight duplicate is `409` with `Retry-After`; a failed request frees its key; a 5xx is not stored; one key against two endpoints is two claims |
| `ConsumerDedupIT` | the same record twice produces one effect; a rolled-back listener gets the record again; a producer-supplied key reused for a different payload is refused |
| `RetentionPurgeIT` | the purge walks several batches and spares the live claims; a replica that cannot take the lease purges **nothing** |
| `NotificationMigrationIT` | every legacy row lands in the shared table with its window and owner intact and the counts reconcile; the migration is idempotent; a newer claim survives it |
| `SqlConfinementTest` | SQL and JDBC stay inside `…idempotency.sql`; the `api` package does not see the implementation; no `@RestControllerAdvice`; no audit SPI; no dependency on `rest-client` or `hot-reload` |
| `RequestFingerprintTest` | key order, whitespace and `charset` do not matter; array order does; a malformed body still fingerprints |

The TTL and lease boundaries are pinned with a **movable clock** rather than by sleeping. That is possible
only because every decision in this module is made against an injected `Clock` and the instant is passed into
the statement as a parameter, instead of the statement calling the database's `now()` — a mechanism whose
expiry cannot be tested at its boundary is a mechanism whose expiry is assumed.

```bash
mvn -pl idempotency-spring-boot-starter -am verify   # unit + architecture + integration
mvn -pl db-core -am verify                           # proves there is no cycle
```
