# reconciliation-spring-boot-starter

[English] | [Русский](README.ru.md)

The inbound twin of [`outbox-spring-boot-starter`](../outbox-spring-boot-starter). The outbox claims
local rows and pushes state **out**; this claims local rows and pulls state **in**. Everything between
"wake up" and "record the outcome" is the same machine, and it lives in
[`job-core`](../job-core), shared by both.

## The problem

Services in this platform hold local records that mirror state owned by external systems, and each
service hand-rolls its own `@Scheduled` polling loop to keep them in sync. Every one of those loops
re-implements the same five stages, and **only stage 3 actually differs between integrations**:

| # | Stage | Varies? | Owned by |
|---|---|---|---|
| 1 | Schedule and leader election | No | this module |
| 2 | **Demand** — which local records need external state right now | In data, not shape | you write a query; the module runs it |
| 3 | **Fetch** — per item / batched / paged / asynchronous job | **Yes. The only real variation** | you write a fetcher |
| 4 | **Reconcile** — apply external state idempotently, without clobbering newer state | The mapping does | you write a reconciler; the module supplies the guard rails |
| 5 | Retry, backoff, quarantine, metrics, audit | No | this module |

A service author writes a demand query, a fetcher and a reconciler. Everything operational is
`application.yaml`, and it is identical across services.

## Quick start

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>reconciliation-spring-boot-starter</artifactId>
</dependency>
```

```java
@ReconciliationTask("billing-status")
public class BillingStatusTask implements SyncTask<Order, String, BillingRecord> {

    private final OrderRepository orders;
    private final BillingClient billing;          // a @LudwigRestClient interface

    @Override public String name() { return "billing-status"; }

    @Override public DemandProvider<Order, String> demand() {
        return new DemandProvider<>() {
            @Override public List<Order> demand(DemandRequest request) {
                return orders.findByStatusIn(NOT_SETTLED, request.maxRecords());
            }
            @Override public Optional<Order> byKey(String key) {
                return orders.findByBillingId(key);
            }
        };
    }

    @Override public Function<Order, String> localKey() { return Order::getBillingId; }
    @Override public KeyCodec<String> keyCodec()        { return KeyCodec.ofString(); }
    @Override public Class<BillingRecord> externalType() { return BillingRecord.class; }

    @Override public Fetcher<String, BillingRecord> fetcher() {
        return (Fetcher.Batched<String, BillingRecord>) billing::statusesFor;
    }

    @Override public ExternalStamp stampOf(BillingRecord record) {
        return ExternalStamp.ofTimestamp(record.changedAt());
    }

    @Override public Reconciler<Order, BillingRecord> reconciler() {
        return (order, record, context) -> {
            if (record.status().equals(order.getStatus())) {
                return ReconcileResult.unchanged();
            }
            order.setStatus(record.status());
            orders.save(order);
            return ReconcileResult.applied("status -> " + record.status());
        };
    }
}
```

```yaml
ludwig:
  reconciliation:
    tasks:
      billing-status:
        fetch:    { shape: batched, batch-size: 200, max-concurrency: 4 }
        schedule: { fixed-delay: 30s, initial-delay: 10s, run-timeout: 5m }
        demand:   { max-records-per-run: 5000, freshness-target: 2m }
        rest-client: billing
```

That is the whole integration. No `@EnableScheduling`, no lock library, no retry loop.

## The core abstraction: key-addressed external record streams

The unification is to stop modelling *a call* and model **a stream of external records addressed by a
correlation key**. All four fetch shapes produce exactly that, and differ only in how the engine walks
them:

```
Demand(Set<K>) ──┬─ PerItem   → concurrent fan-out bounded by max-concurrency
                 ├─ Batched   → chunk(batch-size) → fan-out
                 ├─ Paged     → walk pages, checkpoint the cursor, join against demand
                 └─ JobFetcher→ submit / poll / collect across ticks
                              ↓
                    Stream<FetchOutcome<K,O>>    // Found | NotFound | Failed
                              ↓
                    staging (default) or direct apply, per item, per transaction
```

`Fetcher` is a sealed interface with four `non-sealed` sub-interfaces. Sealed on purpose: the engine
has to know every shape it can be asked to walk, and a fifth added from outside would silently fall
through to no behaviour at all. **Extension here is by choosing a shape, not by inventing one** —
these four are the parts that genuinely differ between integrations.

### `NotFound` is not an error

`FetchOutcome.NotFound` is a first-class outcome, distinct from `Failed`, with its own per-task policy
and its own metric. It matters because "the partner does not know this id" is usually **permanent** —
a record deleted on their side, an id mistyped into this system years ago, a test row — and errors get
retried. Classifying it as a failure produces a record that retries forever, exhausts its budget,
quarantines itself and fires an alert; multiplied across a backlog, it is the single most common cause
of runaway retry loops in this class of system.

| `not-found` | What happens |
|---|---|
| `ignore` (default) | Counted in `reconciliation.records.not_found`, nothing written |
| `mark-missing` | A payload-less row is staged and `Reconciler.reconcileMissing` runs, so "gone upstream" can mean something to the domain |
| `fail` | Treated as a retryable failure. Correct only for a partner that returns 404 for "not ready yet" |

A batched fetcher signals not-found by **leaving the key out of the returned map**. It must not throw,
return `null` or substitute a placeholder — the engine does that mapping once, centrally, so no
individual fetcher has to remember it.

## Two-phase processing: stage, then apply

Fetching and applying in one transaction is what makes these jobs brittle: one bad record poisons the
run, and a retry re-hits the partner for records you already have. The default mode is `staged`.

`sync_inbox_record` is one table for every task, keyed by `task_name`. The apply pass claims rows with
`SELECT … FOR UPDATE SKIP LOCKED`, applies each **in its own transaction**, and records the outcome.
That buys four things:

- per-item retry **without another call to the partner**;
- a replayable record of exactly what the partner said;
- per-record dead-lettering rather than per-run failure;
- an apply cadence independent of the fetch cadence — a task can poll every five minutes and still
  drain deferred records every ten seconds.

A staged row is one of three **kinds**, because three things share that lifecycle but not their
consumer:

| Kind | Written when | Consumed by |
|---|---|---|
| `RECORD` | the partner returned state | the apply pass |
| `MISSING` | the partner does not know the key, under `mark-missing` | the apply pass, via `reconcileMissing` |
| `FETCH_FAILURE` | the call failed | the **fetch** pass, as a backoff ticket |

That third kind is why a key the partner reliably chokes on stops being refetched: it backs off like
anything else and eventually quarantines, visibly, instead of being retried on every run forever with
nothing to show for it.

### `mode: direct`

Available, and an explicit opt-in. It applies inline with no row, which is cheaper and **strictly
worse in every failure mode**: a retry re-hits the partner, and the engine's idempotency short-circuit
and stale-write guard are both unavailable because they are answered from the history of applied
records and there is no history. A reconciler used in direct mode must do its own ordering check. What
direct mode keeps is per-record transaction isolation.

## Correctness guard rails

| Concern | Mechanism |
|---|---|
| **HA double-polling** | Per-task run lease via `job-core`'s `RunLock` — a DB claim with `FOR UPDATE SKIP LOCKED`, consistent with the outbox. No ShedLock, no new dependency |
| **Overlapping runs** | Non-reentrant per-pass guard in `SelfSchedulingLifecycle`, plus `run-timeout` as the lease TTL, plus a stale-record reclaimer for runs whose owner died |
| **Stale-write protection** | The engine rejects external state older by the partner's own timestamp than what has already been applied for that key. Local writes go through JPA so `db-core`'s optimistic locking sees them |
| **Idempotent apply** | The normalized payload is hashed; an unchanged hash short-circuits to `Unchanged` with no write, no `updated_at` churn and no audit row |
| **Per-item isolation** | One transaction per record, in a separate bean so Spring's proxy actually applies it. A failure marks that record, never the run |
| **Poison records** | Attempt counter, exponential backoff with jitter, `QUARANTINED` terminal status, operator requeue |
| **Watermark / delta polling** | `sync_task_state` holds a cursor and a watermark per task and tier; an `incremental` task receives the watermark as `updated-since` |
| **Priority tiers** | Demand splits into a fast `hot` pass and a slow `cold` sweep with separate schedules |
| **Backpressure** | `max-concurrency` per task, partner-scoped quotas and rate limits, and the named REST client's own bulkhead |
| **Graceful shutdown** | `SmartLifecycle` drain with a timeout; in-flight items finish rather than being abandoned mid-claim |

### Why stale-write protection is in the engine

Responses from a partner arrive out of order — a retried call that was slow, a batch that overtook a
per-item fetch, two instances fetching the same key in the same second — and applying the older one
last silently regresses the local record. That bug produces **no error, no log line and no failed
metric**. Leaving it to each reconciler to remember means it is correct in the reconcilers whose
authors thought about it.

Version tokens are compared for equality (an identical token means the same record) but **never for
ordering**: nothing guarantees a partner's tokens sort in issue order, and comparing `"10"` against
`"9"` as text gets it backwards. Only a timestamp decides order. A task whose partner publishes
neither gets no generic protection, and the module says so rather than pretending.

### Why the payload hash is taken over a normalized form

Because the question is "is this the same record as last time?", and most partners do not serialize
identically twice. JSON object key order is not significant and many servers do not stabilize it, so
hashing raw bytes reports every re-serialized record as changed — a write, an `updated_at` bump, an
audit row and a downstream event, on every poll, forever. Object keys are sorted recursively before
hashing. **Array order is not touched**: arrays are ordered by definition, and a partner that reorders
a list has said something different.

## Partner-scoped resources: quotas and rate limits

**A concurrency limit belongs to the external service, not to a task.** Two tasks hitting the same
partner share its budget, so quotas and rate limits are named, top-level resources that tasks
reference by name.

```yaml
ludwig:
  reconciliation:
    quotas:
      partner-exports:
        max-concurrent: 5
        lease-ttl: 15m
        heartbeat-interval: 2m
        acquire-timeout: 0s
        fifo: true
        reclaim: verify-remote
        max-lifetime: 6h
    rate-limits:
      partner-api: { permits: 100, per: 1m }
```

A **quota** is a distributed, leased counting semaphore backed by `sync_quota_lease` and
`sync_quota_waiter`. Resilience4j's bulkhead is per-JVM and cannot express "5 concurrent across the
cluster": three replicas each honouring a limit of five produce fifteen.

Acquisition runs under a Postgres **transaction-scoped advisory lock** keyed on the quota name —
counting live slots and then inserting one is a read-modify-write, and two instances doing it at the
same instant would both see room and both take it, which is precisely what a quota is supposed to
prevent, happening where it is hardest to notice.

### The three rules that make leases safe

All three are required, and each prevents a specific, invisible failure:

1. **Heartbeat; never trust liveness.** A slot is held only while its expiry is in the future. Without
   that, **one crash permanently shrinks the quota by one**, and after enough crashes the integration
   halts entirely — with no error anywhere, because from the inside it is indistinguishable from a
   busy partner. This is the worst failure mode in the design; it has a dedicated test that kills a
   lease holder.
2. **Verify before reclaim.** An expired lease does **not** mean the remote work stopped. The default
   `reclaim: verify-remote` polls the handle first and reclaims only if the job is terminal or past
   `max-lifetime`, cancelling remotely where `cancel` is implemented. `reclaim: on-expiry` exists for
   partners with no status endpoint and **may exceed the partner's stated limit** — document it where
   the integration is described.
3. **Quota lease first, then rate-limiter permit.** Otherwise a task burns permits on work it is not
   allowed to start.

Quotas are **FIFO-fair** by default. Without a queue, "who gets the next free slot" is decided by
whichever tick lands first, and a task on a thirty-second cadence wins essentially every race against
one on a five-minute cadence — the slower task can starve indefinitely while every individual
acquisition looks correct. A missed acquisition stays enqueued, so the next attempt keeps the position
it already waited for. `reconciliation.quota.oldest_waiter.age` is the one number that says whether a
quota is sized correctly; a saturation ratio pinned at 1.0 looks identical whether the queue empties
in seconds or has not moved in an hour.

`acquire-timeout` defaults to `0s`: a pass that cannot get a slot has nothing useful to do but come
back next tick, and waiting pins a scheduler thread for as long as a saturated partner stays
saturated. A non-zero value makes the acquisition retry until the deadline, which is worth it only for
a caller whose surrounding work has already cost something — a rate-limiter permit, a partly built
request — and for which giving up a few hundred milliseconds early is the more expensive answer.

**Rate limits are per instance**, not cluster-wide, and that is a deliberate trade stated plainly: a
rate limiter paces calls this process is about to make, and coordinating it would cost a database
round trip on *every single call*. Divide the partner's published rate by your replica count. A quota
cannot be handled this way, because it counts long-lived remote work where the coordination cost is
negligible and being wrong means running more jobs at the partner than they allow.

## The async-job shape

Some partners answer a request with a job handle; you poll it to completion and then collect the
result. This spans ticks, must survive restarts, and holds a remote resource while it runs.

```
PENDING_SUBMIT → SUBMITTED → RUNNING → SUCCEEDED → COLLECTING → COLLECTED
       │             │           │          │            │
       └─────────────┴───────────┴──────────┴────────────┴──→ FAILED | EXPIRED | CANCELLED
       │
       └──→ ORPHANED
```

```yaml
partner-bulk-status:
  fetch: { shape: async-job, submit-batch-size: 500 }
  quota: partner-exports
  rate-limit: partner-api
  rest-client: partner
  job:
    submit:  { fixed-delay: 1m, max-concurrent-submits: 2 }
    poll:    { fixed-delay: 30s, backoff-multiplier: 1.5, max-interval: 5m, honour-retry-after: true }
    collect: { fixed-delay: 1m, max-concurrency: 1 }
    max-lifetime: 6h
    on-ambiguous-submit: assume-submitted
```

**Three schedulers, each with its own cadence and concurrency.** Probing a hundred running jobs is
cheap and frequent; collecting one result is expensive and rare. Sharing a budget means either the
probes are throttled to the collections' pace — so a job that finished two minutes ago is noticed in
twenty — or the collections inherit the probes' concurrency and the partner's result endpoint is hit
by ten simultaneous multi-megabyte downloads.

### The write order, which cannot be reordered

1. `INSERT sync_remote_job (state = PENDING_SUBMIT, idempotency_key = <uuid>)`, **acquire the quota
   lease**, commit.
2. Submit, passing the idempotency key when the partner honours one.
3. `UPDATE … state = SUBMITTED, external_handle = …`, commit.

If the POST succeeds and the instance dies before step 3, a naive restart resubmits — burning a quota
slot and possibly a **billable remote job that is already running**. Step 1 is what leaves evidence:
the `PENDING_SUBMIT` row turns "we have no idea" into "this is ambiguous, and here is the key to ask
about". Committing the row after the call, or taking the slot after the call, both lose that. Every
deviation from this order is silent in testing and expensive in production.

### Resolving an ambiguous submit

A row stuck in `PENDING_SUBMIT` past `submit-grace-period` is **ambiguous, not lost**.

1. **Ask.** If the fetcher implements `listActive()`, find a job carrying this row's idempotency key
   and adopt its handle. Matching is on the key, never on position or timing — two instances
   submitting concurrently produce two active jobs, and adopting the wrong one attaches this row to
   somebody else's work and leaves the real one unowned.
2. **Apply the policy**, when the partner cannot be asked:

| `on-ambiguous-submit` | Behaviour | Correct for |
|---|---|---|
| `assume-submitted` | Mark `ORPHANED`, hold the slot until `max-lifetime`, never resubmit | Anything expensive or billable. Being wrong costs one wasted slot and a late sync |
| `resubmit` | Settle the row so the next pass starts a fresh job | Cheap, genuinely idempotent submissions only. Otherwise one crash becomes two running jobs |

There is no safe default across partners, so the key is **required** for `shape: async-job` and the
context refuses to start without it.

### Adopt, never restart

On restart, non-terminal job rows are claimed by whichever instance polls next; ownership is rewritten
on every claim. The remote job keeps running and the new owner resumes heartbeating the **existing**
lease rather than releasing it and taking a new slot, which would briefly count one job twice. Demand
covered by any non-terminal job — including an orphan — is excluded from the submit pass, so nothing is
submitted twice. Settling a job is what makes its demand eligible again; there is no separate requeue
flag, because a second source of truth about what still needs syncing would be the stale one.

### Expiry

A job past `max-lifetime` is cancelled remotely where supported, marked `EXPIRED`, and its lease
released — cancel first, then settle, so a partner that can be told to stop is told before this system
stops counting the work. Without a hard stop, a job the partner has quietly abandoned holds a slot
forever while its lease is renewed perfectly: a leak heartbeating cannot detect, because the heartbeat
is working.

## Configuration

Bound from `@ConfigurationProperties("ludwig.reconciliation")`.

### Precedence

**Task, then `defaults`, then the module's built-in value — per field, not per block.** A task that
sets only `retry.max-attempts` keeps the default interval and multiplier rather than dropping the
whole block. The merge is written out in `TaskSettingsResolver` rather than left to relaxed binding,
**because Spring does not do it**: `defaults` and `tasks.billing-status` are two unrelated objects to
the binder, and a service that assumed otherwise would find its defaults silently ignored.

Cron and fixed-delay are inherited as a pair, so a task that sets `cron` does not pick up a default
`fixed-delay` and become contradictory.

### Task settings

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Whether the task runs. A disabled task is still validated |
| `mode` | `staged` | `staged` or `direct` |
| `not-found` | `ignore` | `ignore`, `mark-missing` or `fail` |
| `fetch.shape` | — | Required. Must match the fetcher's interface |
| `fetch.batch-size` | `100` | Keys per call, `batched` |
| `fetch.page-size` | `500` | Records per page, `paged` and job collection |
| `fetch.max-concurrency` | `4` | Calls in flight for this task |
| `fetch.checkpoint` | `true` | Whether a paged sweep persists its cursor |
| `fetch.submit-batch-size` | `500` | Keys per submitted job |
| `schedule.fixed-delay` \| `schedule.cron` | `1m` | When the hot pass runs |
| `schedule.initial-delay` | = fixed-delay | First run after startup |
| `schedule.run-timeout` | `15m` | Run-lease TTL. Must exceed the retry budget |
| `cold-schedule.*` | absent | When the cold sweep runs. Absent means the task is not tiered |
| `demand.max-records-per-run` | `5000` | Hard cap per run |
| `demand.freshness-target` | `15m` | Denominator of the freshness-lag gauge |
| `demand.incremental` | `false` | Whether the watermark is passed as `updated-since` |
| `apply.fixed-delay` | `10s` | Apply-pass cadence |
| `apply.batch-size` | `200` | Records claimed per apply pass |
| `apply.drain-timeout` | `20s` | How long shutdown waits for an in-flight pass |
| `retry.max-attempts` | `8` | Attempts before quarantine |
| `retry.initial-interval` | `2s` | First backoff interval |
| `retry.multiplier` | `2.0` | Growth factor |
| `retry.max-interval` | `10m` | Backoff cap |
| `retry.jitter` | `0.3` | Randomization fraction |
| `audit.enabled` | `true` | Whether anything is audited |
| `audit.persist` | `false` | Whether events also go to `sync_audit_record` |
| `rest-client` / `quota` / `rate-limit` | absent | Named resources, validated to exist |

### Refreshable vs restart

Settings are read where they are used, so what a running process picks up depends on where the value
lands. This matrix is part of the contract:

| Refreshable — takes effect on the next pass | Requires a restart |
|---|---|
| `retry.*` (a fresh calculator is built per outcome) | `fetch.shape` |
| `demand.max-records-per-run`, `demand.freshness-target` | `schedule.*` and `cold-schedule.*` (a pass is scheduled once, at startup) |
| `fetch.batch-size`, `fetch.page-size`, `fetch.max-concurrency` | `apply.fixed-delay` (same reason) |
| `not-found`, `apply.batch-size` | `mode` (staged/direct changes what the schema is used for) |
| `rate-limits.*` (a new limiter config is applied per name) | `quotas.*` (slot counts are read at acquisition against the bound map) |
| `endpoint.allow-destructive-operations` | Adding or removing a task, quota or rate limit |

Anything in the right-hand column changes what is *wired*, and this module wires once at startup on
purpose: a schedule that can change under a running pass is a schedule nobody can reason about during
an incident.

### Startup validation

`ReconciliationConfigurationValidator` refuses to start on a configuration that is individually valid
and jointly wrong, and reports **every** problem in one pass:

- a task referencing an unknown quota, rate limit or REST client;
- `shape: async-job` without a `job` block, or without `on-ambiguous-submit`;
- `run-timeout` shorter than the task's own retry budget;
- `lease-ttl` under `2 × heartbeat-interval`, or `max-lifetime` under `lease-ttl`;
- a `@ReconciliationTask` bean with no configuration, or a configured task with no bean;
- a bean whose annotation and `name()` disagree, or two beans claiming one name;
- a fetcher whose interface contradicts its configured `fetch.shape`.

Every message states **what breaks**, not what is wrong, because the person reading it is deploying at
the time and needs to know whether to roll back.

## Observability

Metrics are tagged `task`, and `quota` where relevant.

| Metric | Type | What it says |
|---|---|---|
| `reconciliation.freshness.lag` | gauge | **The SLO metric.** Seconds past this task's `freshness-target` |
| `reconciliation.run` | timer | Run duration, tagged `tier` and `outcome` |
| `reconciliation.records.fetched` | counter | Records the partner returned |
| `reconciliation.records.settled` | counter | Tagged `outcome`: applied, unchanged, rejected, deferred, missing, failed, quarantined |
| `reconciliation.records.not_found` | counter | Tagged with the policy applied |
| `reconciliation.records.quarantined` | gauge | Current quarantine depth |
| `reconciliation.staged.depth` / `.oldest.age` | gauge | Backlog waiting to be applied |
| `reconciliation.fetch.duration` / `.apply.duration` | timer | Per outbound call / per record |
| `reconciliation.quota.in_flight` / `.limit` / `.saturation` | gauge | Slot occupancy |
| `reconciliation.quota.oldest_waiter.age` | gauge | How long the queue has been waiting |
| `reconciliation.quota.acquire.wait` | timer | Tagged `acquired` |
| `reconciliation.quota.lease.reclaimed` | counter | **Alert on any sustained non-zero value** |
| `reconciliation.job.duration` / `.polls` / `.settled` | timer / summary / counter | Tagged with the terminal state |
| `reconciliation.job.ambiguous_submit` | counter | Tagged `adopted`, `orphaned` or `resubmitted` |

**The two alert-worthy signals:**

- **`reconciliation.freshness.lag`** is the only metric that describes the thing this module exists to
  do. Every other number can look healthy while it climbs: a task whose demand query stopped matching
  anything fetches nothing, succeeds instantly, and reports a perfect run rate forever. The gauge is
  defined as the worse of *the oldest record still in the pipeline* and *time since the last completed
  run*, minus the task's own `freshness-target` — so it reads zero while the task is within its
  declared budget, and one alert threshold works across tasks whose acceptable staleness differs by
  hours.
- **`reconciliation.quota.lease.reclaimed`** at a sustained non-zero rate means either leases are
  leaking or remote work is being duplicated. Both are invisible in everything else, and both get
  worse on their own.

Gauges are registered against suppliers and **pulled**, not set from inside the passes. A gauge only
written while a pass is running keeps reporting the last healthy value of a task that has stopped
running altogether — which is exactly the failure the freshness gauge exists to catch.

**Tracing.** Every run gets a run id, stamped onto every staged row and audit event, and a correlation
id bound for the run's duration. With `observability-spring-boot-starter` on the classpath that id
propagates into the outbound calls the fetcher makes, so one partner interaction is traceable end to
end.

**Audit.** `ReconciliationAuditLogger` is an open SPI with `Slf4j` and `Persisting` implementations.
Recorded: run start/end, per-record outcome transitions, job state transitions, lease
acquire/release/reclaim, and every operator action. One flat `sync_audit_record` table rather than five
shaped ones, because the question asked of an audit trail is almost always "what happened around this
task, in order" — and five tables means five queries and a manual merge at the moment somebody is
working out why a partner was called twice.

## Operations: the `reconciliation` actuator endpoint

```
GET  /actuator/reconciliation                    overview: every task and quota
GET  /actuator/reconciliation/{task}             detail: settings, cursor, in-flight jobs, quarantine
POST /actuator/reconciliation/{task}             {"action": "...", ...}
```

| Action | Parameters | Effect |
|---|---|---|
| `run` | `tier` | Runs a pass now. Returns `ran: false` if one was already in progress |
| `dry-run` | `tier` | Fetches what a run would fetch and reports it per outcome, **writing nothing** — no staged row, no apply, no watermark |
| `backfill` | `keys` | Fetches an explicit comma-separated key list, bypassing the demand query. Keeps every guard rail, and does **not** advance the watermark |
| `reset-cursor` | `tier` | Clears the cursor and watermark |
| `requeue-quarantined` | — | Returns quarantined records to the queue with **reset** attempt counters |
| `requeue-job` | `jobId` | Requeues the records a job's collection produced |
| `cancel-job` | `jobId`, `confirm` | **Destructive.** Marks a job expired; the maintenance pass cancels it remotely and releases its slot |
| `release-lease` | `leaseId`, `confirm` | **Destructive.** Force-releases a quota slot |

Attempt counters are reset on requeue on purpose: an operator requeues because something was *fixed*, so
the record deserves a whole budget against the new conditions rather than the one attempt its old
budget had left.

Destructive actions need **both** `ludwig.reconciliation.endpoint.allow-destructive-operations=true`
and `confirm=true` in the call — the environment where they are permissible and the moment they are
intended are different decisions. Both can make this system's view and the partner's disagree;
force-releasing a lease in particular means the configured limit can now be exceeded. Every mutating
call is audited with the calling principal. Secure the endpoint through
[`security-spring-boot-starter`](../security-spring-boot-starter) like any other.

## Runbook

**The partner is down.** Fetches fail, keys accumulate `FETCH_FAILURE` tickets and back off; nothing is
lost and nothing is applied wrongly. Watch `reconciliation.freshness.lag`. When the partner returns,
keys still inside their budget recover on their own; keys that quarantined need
`requeue-quarantined`. Do **not** restart pods to "clear" it — backoff state is in the database, and a
restart only loses the in-flight work.

**A quota is stuck at zero free slots.** Read `GET /actuator/reconciliation` and look at the quota's
`holders`. If they have `jobId`s, jobs really are running — check their age against `max-lifetime`. If
they do not, or their owners are instances that no longer exist, the reclaim sweep is either not
running or is correctly refusing to reclaim because it cannot verify the remote state; check
`reconciliation.quota.lease.reclaimed` and the logs for "could not verify lease". `release-lease` is
the last resort and may exceed the partner's limit.

**A job is orphaned.** Its submit could not be confirmed and the partner offers no listing. It holds a
slot until `max-lifetime` and nothing will be resubmitted for its keys. Confirm at the partner whether
the job ran. If it did not, `cancel-job` to free the slot and let the demand come back. If it did,
leave it: `max-lifetime` will settle it.

**Records are quarantined.** `GET /actuator/reconciliation/{task}` lists a sample with `lastError`. Fix
the cause, then `requeue-quarantined`. A quarantined `FETCH_FAILURE` means the *call* kept failing for
that key; a quarantined `RECORD` means the *reconciler* kept throwing.

**The freshness lag is climbing but nothing is failing.** The usual cause is a demand query that
stopped matching. Check `reconciliation.records.fetched` — a task fetching zero records while
reporting successful runs is the failure this gauge exists to catch.

## Migrating a hand-rolled `@Scheduled` loop

A typical loop looks like this:

```java
@Scheduled(fixedDelay = 30_000)
public void syncBillingStatuses() {
    for (Order order : orders.findByStatusIn(NOT_SETTLED)) {
        try {
            billing.statusFor(order.getBillingId())
                   .ifPresent(record -> apply(order, record));
        } catch (Exception e) {
            log.warn("sync failed for {}", order.getId(), e);
        }
    }
}
```

1. **The `findBy…` becomes `DemandProvider.demand`**, and add a `byKey` that reloads one record — the
   apply pass needs a fresh one, not the snapshot the query returned.
2. **The client call becomes a `Fetcher`.** If the partner has a batch endpoint, use
   `Fetcher.Batched` and delete the loop; if not, `Fetcher.PerItem` and set `max-concurrency`.
3. **The body of `apply` becomes the `Reconciler`.** Return `Unchanged` when nothing changed — the
   old loop almost certainly wrote unconditionally.
4. **Delete the `try/catch`, the `@Scheduled` and any `@SchedulerLock`.** Retry, backoff, quarantine,
   leader election and metrics are configuration now.
5. **Implement `stampOf`** if the partner publishes a change timestamp. This is the step that is easy
   to skip and worth the most: it is what turns out-of-order responses from an invisible bug into a
   rejected record.
6. **Declare `freshness-target`.** It is how the task says what "working" means for it.

Expect the Java to shrink to about a third and the operational behaviour to become strictly better —
the old loop had no backoff, no isolation, no leader election and no way to tell whether it was behind.

## Schema (PostgreSQL only)

Seven tables, shipped as `db/changelog/reconciliation/reconciliation-changelog.xml` and applied by
`ReconciliationLiquibaseAutoConfiguration` as an independent `SpringLiquibase` bean alongside the
application's own. Set `ludwig.reconciliation.liquibase.enabled=false` to fold it into your own master
changelog.

| Table | Holds |
|---|---|
| `sync_inbox_record` | Staged external records, missing markers and fetch-failure tickets |
| `sync_task_state` | Cursor and watermark, per task and tier |
| `sync_remote_job` | Asynchronous jobs and everything needed to pick one back up |
| `sync_quota_lease` | Occupied quota slots |
| `sync_quota_waiter` | The queue in front of each quota |
| `sync_audit_record` | The persisted audit trail |
| `job_run_lock` | (from `job-core`) Per-task run leases |

Indexes are partial wherever the query is: the claim index covers only claimable statuses, so on a task
that has applied ten million records and has forty waiting it stays proportional to the backlog rather
than to the history.

## Extending it

Everything is a bean with a `@ConditionalOnMissingBean` default: `ReconciliationMetrics`,
`ReconciliationAuditLogger`, `PayloadCodec`, `DemandKeys`, `CorrelationIdSource`, `RestClientPresence`,
`DatabaseQuota`, `RateLimitRegistry`, and every engine service. Publish your own and this module steps
aside. **Adding an integration never requires editing this starter.**

Everything is disable-able: `ludwig.reconciliation.enabled: false` for the module, `enabled: false` per
task.

## Testing

`mvn test` runs:

- **Unit** — property binding and the deep-merge precedence, every validator failure case, the backoff
  curve and its jitter window, payload-hash idempotency and normalization, stale-stamp rejection, the
  `NotFound` policies, demand chunking, and the paged cursor walker.
- **ArchUnit** — the SPI package depends on no implementation and carries no Spring wiring; entities
  are not exposed through it; nothing anywhere uses `@Scheduled`.
- **Integration** (Testcontainers Postgres) — the schema applied from the shipped changelog and
  cross-checked against the JPA mappings by `ddl-auto=validate`; exactly-once run semantics under a
  held lease; per-item failure isolation; retry budget exhaustion → quarantine → requeue; a paged sweep
  interrupted mid-walk and resumed from its checkpoint; watermark advance and monotonicity; the quota
  enforced across two instances; heartbeat renewal; **a lease holder killed mid-job, where
  verify-remote refuses to release a slot for a job still running**; FIFO fairness between two tasks
  sharing a quota; a crash between submit and commit producing no resubmit under `assume-submitted`;
  adoption via `listActive()`; job expiry with remote cancellation; and adoption of an in-flight job
  rather than resubmission.

Integration tests need a running Docker daemon.
