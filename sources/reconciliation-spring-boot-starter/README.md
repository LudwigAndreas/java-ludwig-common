# reconciliation-spring-boot-starter

***English** · [Русский](README.ru.md)*

Keeps local records that mirror externally owned state in sync - the inbound twin of the
[outbox](../outbox-spring-boot-starter). A service writes a demand query, a fetcher and a reconciler instead of a
hand-rolled `@Scheduled` loop; scheduling, leader election, staging, retry with backoff and quarantine, stale-write
protection, payload-hash idempotency, partner-scoped quotas and rate limits, asynchronous partner jobs, collection
mirroring (enumeration tasks), a freshness SLO metric, audit and the operator endpoints of the `reconciliation` administrative area are the module's.

| | |
|---|---|
| Role | starter |
| Depends on (in-repo) | [`audit-core`](../audit-core), [`db-core`](../db-core), [`job-core`](../job-core), [`observability`](../observability-spring-boot-starter), [`rest-client`](../rest-client-spring-boot-starter) |
| Used by | none in this repository (services mirroring partner state) |
| Surfaces | outbound REST (fetchers); administrative area `/admin/v1/reconciliation` |

| # | Stage | Owned by |
|---|---|---|
| 1 | Schedule and leader election | this module |
| 2 | **Demand** - which local records need external state | you write a query |
| 3 | **Fetch** - per item / batched / paged / asynchronous job | you write a fetcher (the only real variation) |
| 4 | **Reconcile** - apply idempotently without clobbering newer state | you write a reconciler; the module supplies the guard rails |
| 5 | Retry, backoff, quarantine, metrics, audit | this module |

**Not for a file that is the authority** - use [file-ingest](../file-ingest-spring-boot-starter). A `SyncTask` is
demand-driven; a partner record with no local row is dropped. A **collection the partner lists** (issues of a query,
records under a source) is mirrored by an [enumeration task](#enumeration-tasks), which creates rows for new members.

## Usage

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

No `@EnableScheduling`, no lock library, no retry loop.

### Fetch shapes

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

`Fetcher` is sealed: extension is by choosing a shape. **`NotFound` is not an error** (usually permanent); a batched
fetcher signals it by leaving the key out of the returned map.

| `not-found` | Effect |
|---|---|
| `ignore` (default) | Counted in `reconciliation.records.not_found`, nothing written |
| `mark-missing` | A payload-less row is staged; `Reconciler.reconcileMissing` runs |
| `fail` | Retryable failure - only for partners returning 404 for "not ready yet" |

### Staged apply (default)

`sync_inbox_record` (one table, keyed by `task_name`) holds what the partner returned; the apply pass claims rows with
`FOR UPDATE SKIP LOCKED` and applies each **in its own transaction**. This gives per-item retry without re-calling the
partner, a replayable record of the partner's answer, per-record dead-lettering, and an apply cadence independent of
fetching.

| Row kind | Written when | Consumed by |
|---|---|---|
| `RECORD` | the partner returned state | apply pass |
| `MISSING` | key unknown, `mark-missing` | apply pass (`reconcileMissing`) |
| `FETCH_FAILURE` | the call failed | **fetch** pass, as a backoff ticket (so a failing key backs off and quarantines instead of being refetched forever) |

`mode: direct` applies inline with no row: cheaper and worse in every failure mode (retries re-hit the partner; no
idempotency short-circuit or stale-write guard - the reconciler must check ordering itself).

### Guard rails

| Concern | Mechanism |
|---|---|
| Double polling in HA | Per-task `RunLock` lease from job-core |
| Overlapping runs | Non-reentrant pass guard, `run-timeout` as lease TTL, stale-record reclaimer |
| **Stale writes** | External state older (by the partner's timestamp) than what was applied for that key is rejected. Version tokens are compared for equality only, never order |
| Idempotent apply | Hash of the payload normalised with object keys sorted (array order kept); unchanged hash → `Unchanged` with no write, no `updated_at` churn, no audit row |
| Per-item isolation | One transaction per record in a separate bean |
| Poison records | Attempts, jittered exponential backoff, `QUARANTINED`, operator requeue |
| Delta polling | `sync_task_state` cursor and watermark per task and tier; `incremental` tasks receive `updated-since` |
| Priority tiers | `hot` pass and a `cold` sweep with separate schedules |
| Backpressure | `max-concurrency`, partner quotas and rate limits, the REST client's bulkhead |
| Shutdown | `SmartLifecycle` drain |

### Quotas and rate limits (partner-scoped)

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

A **quota** is a cluster-wide leased counting semaphore (`sync_quota_lease`, `sync_quota_waiter`), acquired under a
PostgreSQL transaction-scoped advisory lock. Rules: **heartbeat, never trust liveness** (a crash must not shrink the
quota permanently); **verify before reclaim** (`verify-remote` polls the job first; `on-expiry` may exceed the partner's
limit); **quota lease before rate-limit permit**. Quotas are **FIFO-fair** (a fast task cannot starve a slow one).
`acquire-timeout: 0s` returns immediately. **Rate limits are per instance**: divide the partner's rate by replicas.

### Asynchronous partner jobs

```
PENDING_SUBMIT → SUBMITTED → RUNNING → SUCCEEDED → COLLECTING → COLLECTED
       └────────────────────────────────────────────────────────────→ FAILED | EXPIRED | CANCELLED
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

- Three schedulers (submit, poll, collect) with their own cadence and concurrency.
- **Write order is fixed**: insert `sync_remote_job` as `PENDING_SUBMIT` with an idempotency key and acquire the quota
  lease (commit) → submit → record `SUBMITTED` and the handle (commit). A crash after submit leaves evidence, not a
  duplicate.
- **Ambiguous submit** (`PENDING_SUBMIT` past the grace period): if the fetcher implements `listActive()`, adopt the job
  carrying this row's idempotency key; otherwise apply `on-ambiguous-submit` - `assume-submitted` (mark `ORPHANED`,
  hold the slot until `max-lifetime`, never resubmit; for anything billable) or `resubmit` (cheap idempotent submits
  only). Required for `async-job`.
- **Adopt, never restart**: after a restart the next poller adopts the job and keeps heartbeating its existing lease.
- **Expiry**: past `max-lifetime` the job is cancelled remotely where supported, marked `EXPIRED`, its lease released.

Mapping to web-core's `OperationStatus` (the module has no REST surface and does not depend on web-core):
`PENDING_SUBMIT`→`PENDING`; `SUBMITTED`/`RUNNING`/`SUCCEEDED`/`COLLECTING`→`RUNNING` (the partner's "succeeded" is not
"result available"); `COLLECTED`→`SUCCEEDED`; `FAILED`, `EXPIRED`, `CANCELLED` map to themselves; `ORPHANED`→`FAILED`
with `ORPHANED` in `detail`.

### Enumeration tasks

Mirror *everything the partner lists under a scope* - including members with no local row and members that stopped
being listed.

| | `SyncTask` | `EnumerationTask` |
|---|---|---|
| Starts from | local records needing state | a local **scope** row and the partner's listing |
| Partner record with no local row | dropped | handed to you to create |
| Paging state | one cursor per task and tier | cursor, watermark and generation **per scope** |
| "Gone upstream" | `NotFound` for an asked key | absent from a completed full walk |

```java
@ReconciliationEnumeration("jira-issues")
public class JiraIssuesTask implements EnumerationTask<SavedSearch, UUID, JiraIssue, String> {

    @Override public String name() { return "jira-issues"; }

    @Override public ScopeSource<SavedSearch, UUID> scopes() {
        return new ScopeSource<>() {
            @Override public List<SavedSearch> page(UUID after, int limit) { return searches.pageAfter(after, limit); }
            @Override public Optional<SavedSearch> byKey(UUID key)        { return searches.findById(key); }
            @Override public UUID keyOf(SavedSearch search)               { return search.getId(); }
        };
    }
    @Override public KeyCodec<UUID> scopeKeyCodec()          { return KeyCodec.ofUuid(); }
    @Override public Function<JiraIssue, String> memberKey() { return JiraIssue::key; }
    @Override public KeyCodec<String> memberKeyCodec()       { return KeyCodec.ofString(); }

    @Override public MemberFeed<SavedSearch, JiraIssue> feed() {
        return new MemberFeed<>() {
            @Override public PageResult<JiraIssue> fetchPage(SavedSearch search, String cursor, FeedRequest request) {
                return jira.search(search.jql(), request.changedSinceOrEmpty(), cursor, request.pageSize());
            }
            @Override public boolean supportsChangedSince() { return true; }
        };
    }

    @Override public ExternalStamp stampOf(JiraIssue issue) { return ExternalStamp.ofTimestamp(issue.updated()); }

    @Override public MemberReconciler<SavedSearch, JiraIssue, String> reconciler() {
        return new MemberReconciler<>() {
            @Override public MemberResult apply(SavedSearch search, JiraIssue issue, MemberContext context) {
                issues.upsertAndLink(issue, search);          // find by key INCLUDING removed rows
                return MemberResult.applied("synced " + issue.key());
            }
            @Override public LeftResult left(SavedSearch search, String issueKey, MemberContext context) {
                issues.unlink(issueKey, search);              // your decision: unlink, flag, delete
                return LeftResult.HANDLED;
            }
        };
    }
}
```

```yaml
ludwig:
  reconciliation:
    enumerations:
      jira-issues:
        schedule:  { fixed-delay: 1m, run-timeout: 15m }
        discovery: { fixed-delay: 5m }
        refresh:   { full-interval: 6h, incremental-interval: 5m }
        walk:      { page-size: 100, max-concurrency: 2, max-scopes-per-run: 50 }
        sweep:     { confirm-after-misses: 1, max-fraction: 0.5 }
        rate-limit: jira
        rest-client: jira
```

- **discover** registers new scopes; a scope whose local row is gone is forgotten with its membership (no `left`
  calls - your cascade).
- **walk** takes due scopes (never-walked first) from their checkpoint. **Full** on first walk, after
  `refresh.full-interval`, or when the feed cannot filter by change; otherwise **incremental** from the scope's own
  watermark.
- **A page is the transaction** (membership, your writes, cursor). A failing page is replayed item by item; a failing
  member backs off and quarantines; the walk continues.
- **Unchanged items never reach your code** (`sync_scope_member` keeps the last payload hash).
- **Removal is reported, not performed**: after a completed full walk, unlisted members go to `left(...)` once and the
  engine deletes its own membership row only. A returning member arrives with `context.isNew() == true` - your `apply`
  must find soft-removed rows too (a `@SQLRestriction("deleted = false")` hides them; use a plain flag column or a
  lookup that sees them). An unhandled `left` is counted (`members.left_unhandled`).
- **Three sweep guards**: evidence (only a complete full walk with no failed items), plausibility (a sweep larger than
  `sweep.max-fraction` is withheld for an operator), drift (`confirm-after-misses` consecutive misses). A one-member
  scope is always above the default fraction; use `max-fraction: 1` for tiny scopes (and lose the outage guard).

## Configuration

Prefix `ludwig.reconciliation`. **Per-field precedence**: task → `defaults` → built-in (written out in
`TaskSettingsResolver`, because Spring's binder does not merge blocks). Cron and fixed-delay are inherited as a pair.
Enumerations use `enumeration-defaults` the same way.

**Tasks** (`tasks.<name>.*` or `defaults.*`):

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Disabled tasks are still validated |
| `mode` | `staged` | `staged` / `direct` |
| `not-found` | `ignore` | `ignore` / `mark-missing` / `fail` |
| `fetch.shape` | - | **Required**; must match the fetcher interface |
| `fetch.batch-size` | `100` | `batched` |
| `fetch.page-size` | `500` | `paged` and job collection |
| `fetch.max-concurrency` | `4` | |
| `fetch.checkpoint` | `true` | Persist the paged cursor |
| `fetch.submit-batch-size` | `500` | Keys per submitted job |
| `schedule.fixed-delay` \| `schedule.cron` | `1m` | Hot pass |
| `schedule.initial-delay` | = fixed-delay | |
| `schedule.run-timeout` | `15m` | Run-lease TTL; must exceed the retry budget |
| `cold-schedule.*` | absent | Cold sweep (absent = not tiered) |
| `demand.max-records-per-run` | `5000` | |
| `demand.freshness-target` | `15m` | Denominator of the freshness gauge |
| `demand.incremental` | `false` | Pass the watermark as `updated-since` |
| `apply.fixed-delay` / `batch-size` / `drain-timeout` | `10s` / `200` / `20s` | Apply pass |
| `retry.max-attempts` / `initial-interval` / `multiplier` / `max-interval` / `jitter` | `8` / `2s` / `2.0` / `10m` / `0.3` | |
| `audit.enabled` | `true` | |
| `rest-client` / `quota` / `rate-limit` | absent | Named resources, validated to exist |
| `job.*` | - | Async-job schedulers, `max-lifetime`, `on-ambiguous-submit` (required for `async-job`) |

**Enumerations** (`enumerations.<name>.*` or `enumeration-defaults.*`): `enabled` (`true`), `schedule.fixed-delay` |
`schedule.cron` (`1m`), `schedule.run-timeout` (`15m`), `discovery.fixed-delay` (`5m`), `discovery.page-size`
(`1000`), `refresh.full-interval` (`6h` - the longest a removal can go unnoticed), `refresh.incremental-interval`
(absent; requires `supportsChangedSince()`), `walk.page-size` (`100`), `walk.max-concurrency` (`2`),
`walk.max-scopes-per-run` (`50`), `sweep.confirm-after-misses` (`1`), `sweep.max-fraction` (`0.5`), `retry.*`,
`audit.enabled`, `rest-client`, `rate-limit`.

**Top level**: `ludwig.reconciliation.enabled`, `quotas.*`, `rate-limits.*`, `liquibase.enabled` (`true`).

`tasks.<name>.audit.persist` is still bound but has no effect (the trail goes through the platform `AuditSink`).

**Run history** (`ludwig.reconciliation.runs.*`, restart required):

| Property | Default | Meaning |
|---|---|---|
| `manual-concurrency` | `2` | Operator-triggered runs, dry runs and backfills executing at once per replica; beyond it a request is `429` |
| `retention` | `30d` | How long a finished run stays readable |
| `purge-interval` | `1h` | How often old runs are purged and abandoned ones ended |
| `purge-batch-size` | `500` | Runs deleted per committed batch |

| Refreshable (next pass) | Restart required |
|---|---|
| `retry.*`, `demand.max-records-per-run`, `demand.freshness-target`, `fetch.batch-size`, `fetch.page-size`, `fetch.max-concurrency`, `not-found`, `apply.batch-size`, `rate-limits.*`; enumeration `walk`, `sweep`, `refresh`, `retry` | `fetch.shape`, `schedule.*`, `cold-schedule.*`, `apply.fixed-delay`, `mode`, `quotas.*`, enumeration `schedule.*`, `discovery.fixed-delay`, adding/removing tasks, enumerations, quotas or rate limits |

**Startup refuses** (all at once, each message saying what breaks): unknown quota/rate limit/REST client; `async-job`
without a `job` block or `on-ambiguous-submit`; `run-timeout` shorter than the retry budget; `lease-ttl` < 2 ×
`heartbeat-interval`, or `max-lifetime` < `lease-ttl`; a bean without configuration or the reverse; annotation/`name()`
mismatch or duplicate names; a fetcher contradicting `fetch.shape`; an enumeration name that is also a task; incremental
refresh over a feed without change filtering; `sweep.max-fraction` outside (0, 1] or `confirm-after-misses` < 1.

## Deployment

**Schema (PostgreSQL)**: `db/changelog/reconciliation/reconciliation-changelog.xml` (own `SpringLiquibase` bean;
`ludwig.reconciliation.liquibase.enabled=false` to include it yourself). Partial indexes keep claim queries
proportional to the backlog.

| Table | Holds |
|---|---|
| `sync_inbox_record` | Staged records, missing markers, fetch-failure tickets |
| `sync_task_state` | Cursor and watermark per task and tier |
| `sync_remote_job` | Asynchronous jobs |
| `sync_scope_state` | Per enumeration scope: cursor, watermark, generation, due time |
| `sync_scope_member` | Members per scope, last walk seen, payload hash |
| `sync_quota_lease` / `sync_quota_waiter` | Quota slots and the FIFO queue |
| `sync_audit_record` | Created by the changelog; not written (the trail is `audit_event`) |
| `job_run_lock` | From job-core: per-task run leases |

**Administration**: grant `reconciliation.*` to the service's operator roles in `ludwig.admin.grants`; grant
`reconciliation.privileged` only where the destructive actions are intended. **Partners**: a `@LudwigRestClient` per fetcher; rate limits ÷ replicas.

## Observability

Metric prefix `reconciliation.` (tagged `task`, and `quota` where relevant).

| Metric | Type | Meaning |
|---|---|---|
| `reconciliation.freshness.lag` | gauge | **SLO metric**: seconds past the task's `freshness-target` (worse of oldest record in the pipeline and time since the last completed run) |
| `reconciliation.run` | timer | Run duration, `tier`, `outcome` |
| `reconciliation.records.fetched` | counter | Records returned by the partner |
| `reconciliation.records.settled` | counter | `outcome`: applied, unchanged, rejected, deferred, missing, failed, quarantined |
| `reconciliation.records.not_found` | counter | `policy` |
| `reconciliation.records.quarantined` | gauge | Quarantine depth |
| `reconciliation.staged.depth` / `staged.oldest.age` | gauge | Apply backlog |
| `reconciliation.fetch.duration` / `apply.duration` | timer | Per call (`shape`) / per record |
| `reconciliation.quota.in_flight` / `limit` / `saturation` | gauge | Slot occupancy |
| `reconciliation.quota.oldest_waiter.age` | gauge | Whether a quota is sized right |
| `reconciliation.quota.acquire.wait` | timer | `acquired` |
| `reconciliation.quota.lease.reclaimed` | counter | `policy`. **Alert on sustained non-zero** |
| `reconciliation.job.duration` / `job.polls` / `job.settled` | timer / summary / counter | Terminal state |
| `reconciliation.job.ambiguous_submit` | counter | `adopted`, `orphaned`, `resubmitted` |
| `reconciliation.scope.freshness.lag` | gauge | **Enumeration SLO**: most overdue scope (measured from the last listing) |
| `reconciliation.scope.walk` | timer | `kind`, `outcome` |
| `reconciliation.members.settled` | counter | `outcome` incl. `left` |
| `reconciliation.members.left_unhandled` | counter | Removals nobody handles |
| `reconciliation.members.quarantined` | gauge | |
| `reconciliation.sweep.held` | gauge | Withheld sweeps. **Alert on sustained non-zero** |

Gauges are pulled from suppliers (a stopped task cannot keep reporting a healthy value). Every run has a run id on
staged rows and audit events and a correlation id that propagates into outbound calls.

**Recommended alerts:** `reconciliation_freshness_lag > 0` for longer than the task tolerates;
`reconciliation_scope_freshness_lag > 0` likewise; `reconciliation_quota_lease_reclaimed_total` rising;
`reconciliation_sweep_held > 0` for an hour; `reconciliation_records_quarantined` above zero and rising.

## Audit

Category `reconciliation`; resource `sync-task` / task name; the event kind (`RUN`, `RECORD`, `JOB`, `LEASE`,
`OPERATOR`) is an attribute. Recorded: run start/finish/failure, per-record outcome transitions, job transitions, lease
acquire/release/reclaim, enumeration walks, members leaving, quarantines, withheld sweeps, and **every operator action
with the calling principal**. Outcome is inferred from the event name (`.failed`, `.timed-out`, `.reclaimed` →
`FAILURE`; `.quarantined`, `.skipped` → `PARTIAL`).

## Administration

Operator endpoints are on the platform's administrative surface, `/admin/v1/{area}`, and exist only where `security-spring-boot-starter`'s surface does. Each is guarded by one operation; which of the service's roles hold it is the service's own `ludwig.admin.grants` (roles from the authority resolver, never token claims), and an operation granted to no role is refused to everyone. A `privileged` call needs an `Admin-Reason` header, and every call is audited under category `admin` with the operation id as its action. `GET /admin/v1/me/operations` lists what the caller holds.

| Operation | Endpoints | Allows |
|---|---|---|
| `reconciliation.read` | `GET /admin/v1/reconciliation/tasks`<br>`GET /admin/v1/reconciliation/tasks/{task}`<br>`GET /admin/v1/reconciliation/tasks/{task}/scopes/{scope}`<br>`GET /admin/v1/reconciliation/tasks/{task}/runs`<br>`GET /admin/v1/reconciliation/tasks/{task}/runs/{runId}` | See reconciliation tasks, enumerations, scopes, quotas, what is stuck, and every run's outcome. |
| `reconciliation.operate` | `POST /admin/v1/reconciliation/tasks/{task}/runs`<br>`POST /admin/v1/reconciliation/tasks/{task}/backfills`<br>`POST /admin/v1/reconciliation/tasks/{task}/cursor/reset`<br>`POST /admin/v1/reconciliation/tasks/{task}/quarantine/requeue`<br>`POST /admin/v1/reconciliation/tasks/{task}/remotejobs/{jobId}/requeue`<br>`POST /admin/v1/reconciliation/tasks/{task}/discover`<br>`POST /admin/v1/reconciliation/tasks/{task}/members/requeue`<br>`POST /admin/v1/reconciliation/tasks/{task}/scopes/{scope}/walk`<br>`POST /admin/v1/reconciliation/tasks/{task}/scopes/{scope}/preview`<br>`POST /admin/v1/reconciliation/tasks/{task}/scopes/{scope}/reset`<br>`POST /admin/v1/reconciliation/tasks/{task}/scopes/{scope}/sweep/discard` | Run, backfill, requeue and reset reconciliation tasks and enumeration scopes. |
| `reconciliation.privileged` | `POST /admin/v1/reconciliation/tasks/{task}/remotejobs/{jobId}/cancel`<br>`POST /admin/v1/reconciliation/leases/{leaseId}/release`<br>`POST /admin/v1/reconciliation/tasks/{task}/scopes/{scope}/sweep/confirm` | Cancel a remote job, release a quota lease or confirm a withheld sweep - actions that can make this system and the partner disagree. |

**Runs are resources.** Every run that takes its lease - scheduled, manual, dry run or backfill - is recorded with
its kind, who asked, the replica, its correlation id, timestamps, outcome and counts (keys demanded, records fetched,
rows produced); its id is the run id the run's audit events carry. `POST .../runs` and `.../backfills` take the lease
at once (`409` if any replica holds it), answer `202` with the run's envelope and `Location: .../runs/{runId}`, and
run the pass after answering; poll the run resource, which carries `Retry-After` until it is finished. A dry run's
report and a backfill's counts are the run's `result`. When every manual-run slot is busy the request is `429` with
a `retryAfterSeconds` property and nothing is held. Running an enumeration, or a task using the `async-job` shape,
still triggers its walk or submit pass synchronously and answers `200`; those passes are not recorded runs. Runs are
kept for `runs.retention` and purged by `ludwig-reconciliation-run-purge` (listed under `GET /admin/v1/jobs`), which
also ends a run whose replica died as `FAILED` with `run-abandoned`.

**Per-task scope.** Every endpoint naming `{task}` treats the task (or enumeration) name as its administrative object,
and `GET /admin/v1/reconciliation/tasks` lists only the tasks and enumerations the caller's `read` scope admits. With
a data-scope policy for `admin.reconciliation`, a team can be granted the one task it owns: another task is `403` to
operate and `404` to read, the answer a task that does not exist gets. Without one, holding an operation covers every
task. Setup: [data-scope configuration](../../docs/data-scope-configuration.md#administrative-objects).

**Removed:** the `reconciliation` actuator endpoint and `ludwig.reconciliation.endpoint.*`, including `allow-destructive-operations` and the `confirm` parameter. Whether a deployment permits the destructive actions is now whether any of its roles is granted `reconciliation.privileged`; adding `reconciliation` to the actuator exposure list exposes nothing.

**Filtering `GET /admin/v1/reconciliation/tasks/{task}/runs`.** The listing takes an OData `$filter` and no other filter parameter; it pages with `cursor` and `size` in a fixed newest-first order, so `$orderby`, `$top`, `$skip` and `$count` are `400`, as is a field not listed here. The filter is applied under the area's own rules and can only narrow them. Filterable fields: `tier`, `kind` (`SCHEDULED`, `MANUAL`, `DRY_RUN`), `outcome` (`SUCCESS`, `FAILURE`, `TIMED_OUT`), `triggeredBy`, `instance`, `failureCode`, `correlationId`, `submittedAt`, `startedAt`, `finishedAt`, always within the path's task (indexed by task and `submittedAt`). A run in progress is `startedAt ne null and finishedAt eq null`. Not filterable: the result body. Example: `$filter=kind eq 'MANUAL' and outcome eq 'FAILURE'`.

Grant block (the role names are the service's own):

```yaml
ludwig:
  admin:
    grants:
      "[reconciliation.read]": [ORDERS_ADMIN, ORDERS_SUPPORT]
      "[reconciliation.operate]": [ORDERS_ADMIN]
      "[reconciliation.privileged]": [ORDERS_ADMIN]
```

## Runbook

Operator actions, all under `/admin/v1/reconciliation` (see [Administration](#administration) for the operations):

| Action | Endpoint | Effect |
|---|---|---|
| run | `POST /tasks/{task}/runs` `{tier}` | Start a pass now: `202` with the run, poll `Location`; `409` if one is running |
| dry run | `POST /tasks/{task}/runs` `{tier, dryRun: true}` | Fetch and report per outcome, **staging nothing**; the report is the run's `result` |
| backfill | `POST /tasks/{task}/backfills` `{keys}` | Fetch explicit keys, bypassing demand; guard rails kept; watermark not advanced; a run like the others |
| runs | `GET /tasks/{task}/runs?tier=&status=&cursor=`, `GET /tasks/{task}/runs/{runId}` | Every run, newest first, with how it ended and what it counted |
| reset cursor | `POST /tasks/{task}/cursor/reset` `{tier}` | Clear cursor and watermark |
| requeue quarantined | `POST /tasks/{task}/quarantine/requeue` | Requeue with **reset** attempt counters |
| requeue job | `POST /tasks/{task}/remotejobs/{jobId}/requeue` | Requeue a job's collected records |
| cancel job | `POST /tasks/{task}/remotejobs/{jobId}/cancel` | **Privileged**: expire a job; maintenance cancels remotely and frees its slot |
| release lease | `POST /leases/{leaseId}/release` | **Privileged**: force-release a quota slot (may exceed the partner's limit) |

Enumerations: `POST /tasks/{enumeration}/discover`, `POST /tasks/{enumeration}/members/requeue`, and per scope
`POST /tasks/{enumeration}/scopes/{scope}/walk` (`{kind}`), `.../preview` (writes nothing), `.../reset`,
`.../sweep/discard`, `.../sweep/confirm` (**privileged**). Percent-encode scope keys containing `/`. A privileged
action needs the `Admin-Reason` header.

| Situation | What to do |
|---|---|
| Partner is down | Nothing is lost; failures back off. Watch `freshness.lag`. After recovery, `requeue-quarantined` for keys that exhausted their budget. Do not restart pods (backoff state is in the database) |
| Quota stuck at zero free slots | Check holders in the overview. With `jobId`s: jobs are running - compare age to `max-lifetime`. Without, or with dead owners: reclaim is not running or cannot verify (log `could not verify lease`). `release-lease` is the last resort |
| Job `ORPHANED` | Ask the partner whether it ran. Did not: `cancel-job`. Did: leave it; `max-lifetime` settles it |
| Records quarantined | `GET .../{task}` shows samples with `lastError`. Quarantined `FETCH_FAILURE` = the call kept failing; `RECORD` = the reconciler kept throwing. Fix, then `requeue-quarantined` |
| `freshness.lag` climbing, nothing failing | Demand query stopped matching: `records.fetched` is zero while runs succeed |
| Sweep withheld (`sweep.held`) | Read the scope. Real removal: `confirm-sweep`. Outage/permissions: `discard-sweep` and fix; the next walk is full. Scopes keep applying meanwhile |
| Members quarantined | `GET .../{enumeration}` samples; fix; `requeue-members` (affected scopes get a full walk) |
| A run is stuck `RUNNING` | Its replica probably died. The run purge ends it as `FAILED` with `run-abandoned` once it is older than the task's `run-timeout` plus five minutes; the lease expires at `run-timeout`, so the next run is not blocked |
| Manual runs answer `429` | Every `runs.manual-concurrency` slot on that replica is busy; retry after `retryAfterSeconds`, or raise the setting |

## Testing

Unit (binding and per-field precedence, every validator case, backoff and jitter, payload normalisation, stale-stamp
rejection, `NotFound` policies, demand chunking, cursor walking, walk planning), ArchUnit (SPI independent of the
implementation, no `@Scheduled` anywhere), and Testcontainers PostgreSQL integration (schema vs JPA with `validate`;
exactly-once runs under a lease; per-item isolation; retry → quarantine → requeue; paged resume; watermark monotonicity;
quotas across two instances; heartbeats; a killed lease holder with `verify-remote` refusing to release a running job;
FIFO fairness; crash between submit and commit with no resubmit; adoption via `listActive()`; expiry with remote
cancel; enumeration first walk, unchanged short-circuit, incremental vs full, multi-scope members, interrupted walks,
member quarantine, all three sweep guards, trees, operator actions).

### Migrating a hand-rolled loop

1. The `findBy…` becomes `DemandProvider.demand`, plus a `byKey` reload.
2. The client call becomes a `Fetcher` (`Batched` if the partner has a batch endpoint, else `PerItem`).
3. The apply body becomes the `Reconciler`; return `Unchanged` when nothing changed.
4. Delete the `try/catch`, `@Scheduled` and any `@SchedulerLock`.
5. Implement `stampOf` if the partner publishes a change timestamp (turns out-of-order responses into rejected
   records).
6. Declare `freshness-target`.

## Design decisions

- **Stale-write protection in the engine**, not in each reconciler: the bug produces no error, log or metric.
- **Quotas are cluster-wide**, rate limits per instance (a database round trip per call would cost more than it saves).
- **Fixed write order for async jobs**; the `PENDING_SUBMIT` row is the evidence that makes recovery possible.
- **Removal is reported, never performed** on your tables, behind three guards.
- **Everything is a `@ConditionalOnMissingBean` default** (`ReconciliationMetrics`, `PayloadCodec`, `DemandKeys`,
  `CorrelationIdSource`, `DatabaseQuota`, `RateLimitRegistry`, engine services); adding an integration never edits this
  starter.
