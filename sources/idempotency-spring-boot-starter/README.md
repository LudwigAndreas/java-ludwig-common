# idempotency-spring-boot-starter

***English** · [Русский](README.ru.md)*

The platform's work-dedup store: it claims a caller-supplied key in a scope so the same request cannot be
acted on twice. On top of the store: an HTTP filter that **replays the original response** for a retried
request, request fingerprinting so a reused key is refused instead of answered with someone else's
response, a Kafka record filter that claims inside the listener's transaction, and a batched retention
purge.

| | |
|---|---|
| Role | starter |
| Depends on (in-repo) | [`audit-core`](../audit-core), [`db-core`](../db-core), [`job-core`](../job-core), [`web-core-spring-boot-starter`](../web-core-spring-boot-starter) |
| Used by | `file-action`, `grpc-server`, `messaging` starters; `notification-service` |
| Surfaces | REST (filter on opted-in endpoints), Kafka (record filter) |

The correctness is in one statement:

```sql
INSERT INTO idempotency_claim AS t (…)
VALUES (…)
ON CONFLICT (scope, idempotency_key) DO UPDATE SET
    request_id = CASE WHEN <reclaimable> THEN EXCLUDED.request_id ELSE t.request_id END,
    …                                     -- one assignment of this shape per column
RETURNING request_id, state, fingerprint, lease_expires_at, expires_at, response_*
```

- `DO UPDATE` (not `DO NOTHING`) makes the loser of a race **block on the winner's row lock and read the
  committed winner**: no exception, no rollback, no double execution. `DO NOTHING` would return no row, and
  a `READ COMMITTED` re-select can miss an uncommitted winner. Read-then-insert aborts a transaction that
  has already done work.
- The unique constraint `uk_idempotency_claim_key` is the enforcement.
- The conflict branch **reclaims** a free claim (window passed, lease expired, or `FAILED`) in the same
  statement, so the TTL is exact and does not depend on when the purge last ran.

The SQL lives in `ru.ludwigandreas.idempotency.sql`, the module's documented exception to QueryDSL-only
(JPQL has no `ON CONFLICT` / `RETURNING`), fenced by `SqlConfinementTest`.

## Usage

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>idempotency-spring-boot-starter</artifactId>
</dependency>
```

### Claim modes

Choosing the mode is required at every call site; there is no default.

| | `TRANSACTIONAL` | `STANDALONE` |
|---|---|---|
| transaction | the caller's (refused if none is open) | its own |
| lifecycle | commits with the work; a rollback frees the key | `IN_PROGRESS → COMPLETED \| FAILED`, leased |
| a duplicate is told | "done, owned by request X" | "done, here is the response" or "in flight" |
| dead holder | impossible (never committed) | lease expires, key becomes claimable |
| for | a consumer whose unit of work is one transaction | the HTTP filter; work spanning transactions or calling out |

`STANDALONE` needs a lease: a key stuck `IN_PROGRESS` behind a dead process would block the caller's retry
forever. A `FAILED` claim is immediately reclaimable.

### HTTP

Off by default (`ludwig.idempotency.http.enabled`), so adding the dependency never changes a live API.
Opt an endpoint in with `@Idempotent` on the handler, or with `path-patterns` for endpoints the service
does not own. The client sends `Idempotency-Key`.

| The key is | Response |
|---|---|
| fresh | Handler runs; its response is stored |
| completed | Stored response **replayed** (status, body, allow-listed headers), plus `Idempotency-Replayed`; handler does not run |
| in flight | `409 ludwig.idempotency.error.in-progress` with `Retry-After` |
| reused for a different request | `422 ludwig.idempotency.error.fingerprint-mismatch`; the first response is **never** returned |
| completed but response too large to store | `409 ludwig.idempotency.error.not-replayable`, no `Retry-After` (retrying will not help) |
| missing (with `key-required`) / too long | `400 key-required` / `400 key-too-long` |

- Not stored: a **truncated** body, and any **4xx/5xx** - those mark the claim `FAILED` and free the key, so
  a transient failure is not made permanent and a corrected request is not refused.
- Problems are RFC 9457 documents rendered through web-core's `ProblemDetailFactory` and bundles (the filter
  runs outside Spring MVC's exception handling, so it calls the same beans).
- `methods` defaults to `POST`, `PATCH`. `PUT` and `DELETE` are idempotent by RFC 9110; a replay would hide a
  genuine state change from the second caller.
- `replayed-headers` is an allow-list (`Location`, `ETag`, `Content-Language`): replaying `Set-Cookie` would
  hand a second caller the first caller's session.

**Fingerprinting** prevents a reused key from returning another request's response:

- a hash, not the body (no payload table to redact and protect);
- JSON is canonicalised (keys sorted, whitespace and `charset`/`boundary` ignored), so a re-serialised retry
  still matches; **array order is kept** (ordered by specification);
- a claim without a stored fingerprint never mismatches.

### Kafka

`IdempotentRecordFilterStrategy` claims before the listener body runs, in `TRANSACTIONAL` mode, inside the
container's transaction. [messaging](../messaging-spring-boot-starter#consumer-side-dedup) attaches it to
every factory it builds when `ludwig.idempotency.kafka.enabled=true`, and supplies the transaction manager.
Without messaging:

```java
factory.setRecordFilterStrategy(idempotentRecordFilterStrategy);
factory.getContainerProperties().setTransactionManager(transactionManager);
```

- Default key: the record's coordinates `topic:partition:offset` (identifies a delivery). Set `key-header`
  when producers republish one logical event at a new offset; the platform producer's header is
  `ludwig-idempotency-key`.
- Never the record's message key - it groups related records and would dedup all events of one entity to
  the first.
- Do not put dedup in front of a converging projection (last-write-wins); it is already correct on replay.

### Sending a key downstream

`IdempotencyHeaders.KEY` (`Idempotency-Key`) is the header name to use when calling another service so the
far side can dedup.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `ludwig.idempotency.enabled` | `true` | Master switch |
| `ludwig.idempotency.backend` | `postgres` | `postgres` or `redis` (see [Backends](#backends)) |
| `ludwig.idempotency.ttl` | `24h` | **Correctness parameter**: the window in which a retry is recognised. Shortening it turns duplicates into double executions |
| `ludwig.idempotency.lease` | `60s` | How long an in-progress standalone claim survives without renewal |
| `ludwig.idempotency.scopes[].scope` / `.ttl` | - | Per-scope TTL (e.g. a Kafka topic needs longer than its retention) |
| `ludwig.idempotency.http.enabled` | `false` | Enable the HTTP filter |
| `ludwig.idempotency.http.path-patterns` | `[]` | Extra endpoints; empty = only `@Idempotent` handlers |
| `ludwig.idempotency.http.methods` | `POST`, `PATCH` | Methods the filter applies to |
| `ludwig.idempotency.http.key-required` | `false` | Reject requests without a key |
| `ludwig.idempotency.http.scope-per-endpoint` | `true` | One key on two endpoints is two claims |
| `ludwig.idempotency.http.fingerprint-requests` | `true` | Fingerprint request bodies |
| `ludwig.idempotency.http.max-fingerprinted-body` | `262144` | Bytes hashed |
| `ludwig.idempotency.http.max-stored-response` | `262144` | Larger responses are not stored (→ `not-replayable`) |
| `ludwig.idempotency.http.replayed-headers` | `Location`, `ETag`, `Content-Language` | Allow-list of replayed headers |
| `ludwig.idempotency.http.min-stored-status` / `max-stored-status` | `200` / `299` | Statuses stored for replay |
| `ludwig.idempotency.kafka.enabled` | `false` | Enable the record filter |
| `ludwig.idempotency.kafka.mode` | `TRANSACTIONAL` | Claim mode for records |
| `ludwig.idempotency.kafka.key-header` | unset | Header used as key; unset = record coordinates |
| `ludwig.idempotency.kafka.fingerprint-records` | `true` | Refuse a reused key with a different payload |
| `ludwig.idempotency.purge.enabled` | `true` | Run the retention purge |
| `ludwig.idempotency.purge.interval` | `15m` | Purge interval |
| `ludwig.idempotency.purge.initial-delay` | `5m` | Delay before first purge |
| `ludwig.idempotency.purge.batch-size` | `1000` | Rows per batch |
| `ludwig.idempotency.purge.max-batches-per-run` | `100` | Batches per run |
| `ludwig.idempotency.purge.lock-name` | `ludwig-idempotency-purge` | `RunLock` name |
| `ludwig.idempotency.redis.key-prefix` | `ludwig:idempotency` | Redis key prefix |
| `ludwig.idempotency.liquibase.enabled` | `true` | Apply the changelog as an own bean |

### Backends

| | PostgreSQL (default) | Redis |
|---|---|---|
| `TRANSACTIONAL` | yes | **no** (throws) |
| `STANDALONE` | yes | yes |
| purge | this module's job | Redis key expiry |
| reclaim of an abandoned claim | mutually exclusive (row lock) | best-effort (last writer wins) |

A Redis claim commits in a different system from the work it protects; a crash between them leaves a key
claimed for work that never happened, bounded by the lease. Acceptable for the HTTP filter in front of an
idempotent handler; **not acceptable for Kafka consumers**.

## Deployment

**Schema (PostgreSQL).** `db/changelog/idempotency/idempotency-changelog.xml`:

- `idempotency-0001` creates `idempotency_claim` (`scope`, `idempotency_key` unique together, `request_id`,
  `state`, `fingerprint`, `lease_expires_at`, `failure_reason`, `response_*`, `created_at`, `expires_at`)
  with an index on `expires_at`.
- `idempotency-0002` copies rows from a legacy `notification_idempotency` table if it exists (state
  `COMPLETED`, original `created_at`/`expires_at`, no fingerprint), skipping keys already claimed in the new
  table. It is `runAlways` with a `tableExists` precondition, so it is a no-op elsewhere.

**Redis** (only with `backend=redis`): a reachable Redis via `spring.data.redis.*`.

**Purge** runs on one replica under the `RunLock` `ludwig-idempotency-purge` (needs `job_run_lock` from
[`job-core`](../job-core)).

## Observability

**Metrics:**

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `ludwig.idempotency.claims` | counter | `scope`, `outcome` (`CLAIMED`, `IN_PROGRESS`, `COMPLETED`) | Claim attempts |
| `ludwig.idempotency.replays` | counter | `scope` | Stored responses replayed |
| `ludwig.idempotency.claims.failed` | counter | `scope` | Claims freed after a failed attempt |
| `ludwig.idempotency.fingerprint.mismatches` | counter | `scope` | **A client reuses one key for different requests** |
| `ludwig.idempotency.purged` | counter | - | Expired claims removed |

Only `scope` is a tag; key, request id and caller are unbounded and never tags.

**Logs.** `WARN` for a reused key (HTTP and Kafka), a lost purge lock, a purge that left expired claims
("consider raising" batch size or frequency), unreadable stored headers, a claim no longer held at
completion. `INFO` for a discarded Kafka redelivery and for purge totals.

**Recommended alerts:**

| Condition | Why |
|---|---|
| `increase(ludwig_idempotency_fingerprint_mismatches_total[1h]) > 0` | A client is broken; it sees occasional 422s nobody reports |
| Log `Idempotency purge left ... expired claims` repeatedly | Purge cannot keep up; table grows |

Replays and duplicates are healthy traffic, not alert conditions.

**Known gaps:** no gauge for the number of `IN_PROGRESS` claims or table size.

## Audit

| Field | Replay | Fingerprint mismatch |
|---|---|---|
| category | `idempotency` | `idempotency` |
| action | `idempotency.replayed` | `idempotency.fingerprint-mismatch` |
| resource | `idempotency-key` / key | same |
| outcome | `SUCCESS` | `DENIED` (refused on purpose, correctly) |

No event per successful claim (that would be one row per request). Neither event carries the request body
or fingerprints: a fingerprint would let a reader confirm a guess about a request's content.

## Runbook

| Symptom | Likely cause | What to do |
|---|---|---|
| Clients get `409 in-progress` for a long time | Holder died; claim waits for `lease` to expire | Waits at most `lease` (60s). If stuck longer: `SELECT * FROM idempotency_claim WHERE scope = '<s>' AND idempotency_key = '<k>';` - check `lease_expires_at` |
| `422 fingerprint-mismatch` reported by a client | Client reuses keys across different requests | Client bug; the client must generate a new key per logical request |
| Duplicate side effects despite the filter | Endpoint not opted in, method not in `methods`, TTL shorter than client retry window, or Redis backend crash window | Check `@Idempotent` / `path-patterns`, `methods`, `ttl` |
| Kafka message never processed and no error | Claim committed outside the listener transaction | Use messaging's builder (it attaches the transaction manager) or set it on the container |
| `idempotency_claim` grows | Purge disabled or lock held elsewhere | Check `purge.enabled`, the log, and `SELECT * FROM job_run_lock WHERE lock_name = 'ludwig-idempotency-purge';` |
| `TRANSACTIONAL claim must run inside the caller's transaction` | `TRANSACTIONAL` claim with no transaction | Call within `@Transactional`, or use `STANDALONE` |
| Release a claim manually (client must re-run) | - | `UPDATE idempotency_claim SET state = 'FAILED' WHERE scope = '<s>' AND idempotency_key = '<k>';` - the next request reclaims it. Only if the work is known not to have happened |

## Testing

Integration tests on real PostgreSQL (Docker); single-threaded tests prove nothing here.

| Suite | Pins |
|---|---|
| `IdempotencyStoreIT` | N threads → one winner, losers get the winner's id; rollback frees the key; `TRANSACTIONAL` without a transaction refused; state transitions; lease expiry; TTL boundary ±1s; scopes as namespaces; selective purge |
| `IdempotencyHttpIT` | retried `POST` replays `201`, body and `Location` with the handler run once; re-serialised retry matches; reused key → 422 without the first response; in-flight → 409 + `Retry-After`; failed request frees its key; 5xx not stored |
| `ConsumerDedupIT` | duplicate record → one effect; rolled-back listener gets the record again; reused producer key with different payload refused |
| `RetentionPurgeIT` | multi-batch purge spares live claims; replica without the lease purges nothing |
| `NotificationMigrationIT` | legacy rows migrate with windows intact; idempotent; newer claims survive |
| `SqlConfinementTest` | SQL stays in `…sql`; no `@RestControllerAdvice`; no audit SPI; no dependency on rest-client or hot-reload |
| `RequestFingerprintTest` | key order / whitespace / charset irrelevant; array order relevant |

Boundaries use a movable `Clock` (the instant is a statement parameter, not `now()`).

## Design decisions

- **Seven "idempotency" mechanisms exist; two live here** (claiming a key; sending one downstream). Outbox
  publish dedup, a key column on an export run, file-ingest's `(bucket, key, etag)` constraint, RFC 9110
  retry classification and last-write-wins projections are correct local answers and stay where they are.
- **One module, not core + starter**: nothing below `db-core` needs the store. If rest-client or hot-reload
  ever does, split out `idempotency-core` first; `SqlConfinementTest` guards the absence of those edges.
- **Mode chosen per call site**; the wrong mode is a concurrency-only bug.
- **`TransactionTemplate`, not `@Transactional`**: the mode is a runtime value, and self-invocation would
  bypass the proxy silently.
