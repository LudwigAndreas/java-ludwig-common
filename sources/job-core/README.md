# job-core

***English** · [Русский](README.ru.md)*

The building blocks of every scheduled, database-backed worker in the platform: exponential backoff with
jitter, a self-scheduling lifecycle that drains on shutdown, the `FOR UPDATE SKIP LOCKED` claim
statement, the instance identity written to lock-owner columns, and the platform's only distributed
lock. It has no jobs of its own. One implementation of each piece means every worker - outbox,
reconciliation, export, ingest, purges - behaves the same way during a partner outage or a rolling
deploy.

| | |
|---|---|
| Role | library (with auto-configuration) |
| Depends on (in-repo) | nothing (test: `test-support`) |
| Used by | `audit`, `export`, `file-action`, `file-ingest`, `idempotency`, `messaging-db`, `outbox`, `reconciliation` starters; `notification-service` |
| Surfaces | none |

| Piece | What it is |
|---|---|
| `BackoffPolicy` / `BackoffCalculator` | Exponential backoff with multiplier, cap and jitter, computed for a persisted `next_attempt_at` |
| `ScheduleSpec` / `SelfSchedulingLifecycle` / `ScheduledJob` | A `SmartLifecycle` base that schedules itself on an injected `TaskScheduler`, never runs twice at once, contains exceptions and drains on shutdown |
| `SkipLockedClaim` | The `UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING *` statement |
| `ClaimOwner` / `JobInstanceIdentity` | The string this process writes into `locked_by` / `owner` columns |
| `RunLock` / `JdbcRunLock` | A leased, `run_id`-fenced, cluster-wide lock for a named unit of scheduled work |
| `RunLockListener` / `MicrometerRunLockListener` | Optional lock instrumentation |

## Usage

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>job-core</artifactId>
</dependency>
```

Runtime dependencies: Spring Context, Spring Boot autoconfigure, Jakarta Persistence API (for the JPA
flavour of the claim helper; no entities are mapped), resilience4j-core, SLF4J. Liquibase and Micrometer
are optional.

### Backoff

```java
BackoffCalculator backoff = new BackoffCalculator(
        new BackoffPolicy(Duration.ofSeconds(2), 2.0, Duration.ofMinutes(10), 0.3));

message.setNextAttemptAt(Instant.now().plus(backoff.nextDelay(message.getAttempts())));
```

`attempt` is the 1-based attempt count **after** the failure that just happened: the first failure
passes `1` and gets `initialInterval`.

The delay is written to a row and picked up by a later tick; nothing sleeps in-call. resilience4j-core is
used only as an interval function, never as `@Retry`, which would hold a thread and a database connection
for the whole backoff and lose it if the pod is rescheduled.

**Jitter** spreads retries of records that failed together, so a partner that has just recovered is not
knocked over by the whole backlog at once. Use `0.2`-`0.3` in production; `0` makes the curve
deterministic for tests.

### Scheduling

```java
public class BillingStatusPoller extends SelfSchedulingLifecycle {

    public BillingStatusPoller(TaskScheduler scheduler) {
        super("billing-status", scheduler,
                ScheduleSpec.fixedDelay(Duration.ofSeconds(30), Duration.ofSeconds(10)),
                Duration.ofSeconds(20));
    }

    @Override
    protected void runOnce() {
        // one cycle; let exceptions propagate - the base class logs which job failed
    }
}
```

Or, when the body lives in a service bean: `new ScheduledJob(name, scheduler, spec, drainTimeout,
service::pollOnce)`.

| Property | Why |
|---|---|
| **Not `@Scheduled`** | A starter must work without the service enabling `@EnableScheduling`; a forgotten annotation would otherwise mean a silent backlog |
| **Fixed delay, never fixed rate** | A slow run delays the next one instead of piling up overlapping runs when the system is already under stress |
| **Non-reentrant** | A second entry (cron overlap, an administrative run mid-run) is a logged no-op, not a second concurrent claimer |
| **Drains on stop** | `stop()` cancels without interrupting, then waits up to `drainTimeout`, so claimed rows are not stranded until a stale reclaimer finds them. Keep `drainTimeout` below the pod's termination grace period |

`runNow()` shares the non-reentrancy guard and returns whether it ran. The administrative run,
`POST /admin/v1/jobs/{name}/run`, does not call it on the request thread: it uses `runSoon()`, which brings the next
scheduled run forward on the job's own scheduler and answers `409` while any replica holds the job's run lease.

### Claiming

```java
List<OutboxMessage> claimed = SkipLockedClaim.claim(entityManager, OutboxMessage.class,
        "outbox_message",
        "status = 'PROCESSING', locked_at = now(), locked_by = :lockOwner",
        "t.status IN ('PENDING', 'FAILED') AND t.next_attempt_at <= :now",
        "t.created_at, t.id",
        batchSize,
        Map.of("lockOwner", lockOwner, "now", Instant.now()));
```

renders

```sql
UPDATE outbox_message
   SET status = 'PROCESSING', locked_at = now(), locked_by = :lockOwner
 WHERE id IN (SELECT t.id FROM outbox_message t
               WHERE t.status IN ('PENDING', 'FAILED') AND t.next_attempt_at <= :now
               ORDER BY t.created_at, t.id
               LIMIT :jobClaimLimit
               FOR UPDATE SKIP LOCKED)
RETURNING *
```

- **The lock clause is on the subquery.** PostgreSQL cannot attach one to an `UPDATE`, and update row
  locks block. With `SKIP LOCKED` in the subquery, N instances share the work instead of taking turns.
- **`RETURNING *`.** One round trip and no window where a row is claimed but not loaded.
- **A total `ORDER BY`.** Otherwise a backlog can starve its oldest rows indefinitely.

Fragments and the table name come from module code, never from configuration or a request; every value is
a bound parameter. There is a JPA overload (`:name` parameters) and a JDBC overload (`?` parameters).

### The run lock

The platform's only distributed lock. Anything that must run on exactly one replica takes it from here.

```java
boolean ran = runLock.runIfAvailable("billing-status", handle -> {
    for (Batch batch : batches) {
        if (!handle.renew(runLock.defaultLeaseTtl())) {
            return;               // superseded: another replica is redoing this run
        }
        process(batch);
    }
});
```

- `false` is a normal outcome: on three replicas, two skip every run.
- The lease is released in a `finally`, also when the work throws, so the next run is not blocked for a
  full lease. The exception propagates unchanged.
- Overloads: an explicit `Duration`, and the primitive:

```java
Optional<RunLockHandle> held = runLock.tryAcquire("billing-status", Duration.ofMinutes(5));
if (held.isEmpty()) {
    return;                       // another instance has this run
}
try (RunLockHandle lease = held.get()) {
    // periodically: if (!lease.renew(Duration.ofMinutes(5))) { stop immediately; }
}
```

**Rules callers must follow:**

1. **A lease is not proof you still hold it.** `renew` returns whether it is still yours. On `false`, stop
   immediately - another instance already took over.
2. **`tryAcquire` never blocks.** A job that cannot get the lease returns and tries next tick.
3. **Do not lock work that `SKIP LOCKED` already partitions.** A per-row poller needs no lock; a lock would
   reduce N replicas to one. Locks are for work over a *set* of rows: digests, retention purges,
   compactions.

The behaviour is specified as the `run-lock` capability in
[`openspec/specs/run-lock/spec.md`](../../openspec/specs/run-lock/spec.md); a behaviour change updates the
spec first.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `ludwig.job-core.enabled` | `true` | Master switch |
| `ludwig.job-core.owner` | hostname + random suffix | Identity written into lock-owner columns |
| `ludwig.job-core.lock.default-lease` | `5m` | Lease used by `runIfAvailable(name, work)`. Also the failover time: a pod that dies holding the lock blocks the job for this long |
| `ludwig.job-core.liquibase.enabled` | `true` | Apply this module's changelog as its own `SpringLiquibase` bean |

Schedules, batch sizes and retry budgets are configured by the module that owns each job, under its own
prefix. `BackoffPolicy` and `ScheduleSpec` are value types, not property classes, for that reason. A job
whose runs differ greatly from 5 minutes passes its own lease to the explicit overload.

The instance identity is **not** stable across restarts: a restarted pod is a new owner, and adopting its
predecessor's work is an explicit reclaim step.

## Deployment

**Schema (PostgreSQL only).** `db/changelog/job-core/job-core-changelog.xml` creates `job_run_lock`
(`lock_name` unique, `owner`, `run_id`, `acquired_at`, `heartbeat_at`, `expires_at`). Applied by
`JobCoreLiquibaseAutoConfiguration` as an independent `SpringLiquibase` bean; set
`ludwig.job-core.liquibase.enabled=false` to include it from your own master changelog instead.

`expires_at` is the single source of truth for "held". A release clears `owner` and sets
`expires_at = now()`; rows are not deleted, so the table holds one row per lock name.

Lease operations use their own short auto-commit JDBC connections, not the caller's transaction: a lease
must be visible to other instances immediately and must not roll back with a failed run. No transaction
manager, entity or entity scanning is required.

## Observability

**Metrics** (registered when Micrometer and a `MeterRegistry` bean exist):

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `ludwig.job.lock.acquisition` | counter | `lock`, `acquired` | Acquisition attempts. Every replica trying and none acquiring for longer than the lease means a stranded lease |
| `ludwig.job.lock.lost` | counter | `lock` | A renewal found the lease gone: a run was superseded mid-work. **Alert on any** |

The owner is not a tag (random suffix → unbounded cardinality); it is in the log lines.

**Logs:**

| Level | Message | Meaning |
|---|---|---|
| `ERROR` | `Job '<name>' run failed` | A cycle threw; the next cycle runs on schedule |
| `WARN` | `Job '<name>' did not finish within the <t> drain timeout` | Shutdown abandoned in-flight work; stale reclaimers will recover it |
| `WARN` | `Run lock '<name>' was lost by <owner> (run <id>)` | Lease expired and was taken over |
| `WARN` | `Could not acquire / renew / release run lock '<name>'` | Database error during a lease operation; treated as held elsewhere / lost / left to expire |

**Recommended alerts:**

| Condition | Why |
|---|---|
| `increase(ludwig_job_lock_lost_total[15m]) > 0` | Work was done twice or abandoned half done; lease too short for the run |
| `sum by (lock) (increase(ludwig_job_lock_acquisition_total{acquired="true"}[1h])) == 0` while the job is scheduled | The job is not running anywhere |
| Rate of `Job '...' run failed` log lines | A worker failing every cycle |

**Known gaps:** `SelfSchedulingLifecycle` / `ScheduledJob` publish no metrics of their own - no run count,
failure count, duration, or time of last successful run per job. A worker that fails every cycle is
visible only in the log, and one that stopped scheduling is not visible at all. Every worker in the
platform inherits this gap; a per-job timer and a "last success" gauge here would close it for all of them.

## Audit

Not applicable: the module performs no business state changes. Modules built on it audit their own
operations.

## Administration

Operator endpoints are on the platform's administrative surface, `/admin/v1/{area}`, and exist only where `security-spring-boot-starter`'s surface does. Each is guarded by one operation; which of the service's roles hold it is the service's own `ludwig.admin.grants` (roles from the authority resolver, never token claims), and an operation granted to no role is refused to everyone. A `privileged` call needs an `Admin-Reason` header, and every call is audited under category `admin` with the operation id as its action. `GET /admin/v1/me/operations` lists what the caller holds.

| Operation | Endpoints | Allows |
|---|---|---|
| `jobs.read` | `GET /admin/v1/jobs`<br>`GET /admin/v1/jobs/locks` | See the scheduled jobs and who holds each run lease. |
| `jobs.operate` | `POST /admin/v1/jobs/{name}/run` | Run a scheduled job now. |
| `jobs.privileged` | `POST /admin/v1/jobs/locks/{name}/release` | Force-release a run lease whose holder is gone. |

Grant block (the role names are the service's own):

```yaml
ludwig:
  admin:
    grants:
      "[jobs.read]": [ORDERS_ADMIN, ORDERS_SUPPORT]
      "[jobs.operate]": [ORDERS_ADMIN]
      "[jobs.privileged]": [ORDERS_ADMIN]
```

## Runbook

| Symptom | Likely cause | What to do |
|---|---|---|
| A singleton job has not run since a pod died | Lease held by the dead pod until `expires_at` | Wait for `default-lease` (5 min by default). To release now: `UPDATE job_run_lock SET owner = NULL, expires_at = now() WHERE lock_name = '<name>';` - safe only if no live instance is running that job |
| `ludwig_job_lock_lost_total` increases | Run takes longer than the lease and does not renew often enough, or database stalls delayed renewals | Renew per batch; pass a longer lease to `runIfAvailable(name, ttl, work)` for that job |
| `Job '<name>' did not finish within the drain timeout` on every deploy | `drainTimeout` too short for a cycle, or the cycle is too large | Reduce batch size or raise drain timeout (still below the termination grace period) |
| Who holds a lock? | - | `SELECT lock_name, owner, run_id, heartbeat_at, expires_at FROM job_run_lock ORDER BY lock_name;` |
| Startup fails on `job_run_lock` changeset | Changelog included twice (own master changelog and the starter's bean) | Set `ludwig.job-core.liquibase.enabled=false` when including it yourself |

## Testing

- `mvn test`: backoff curve and jitter window, `ScheduleSpec` validation, lifecycle non-reentrancy,
  exception containment, drain-on-stop.
- `mvn verify`: `JdbcRunLockIT` against real PostgreSQL (Docker): one live lease admits one instance; a
  lapsed lease is claimable without release; renewal after a lapse and re-acquisition by the same owner
  fails (`run_id` fence); release is immediate; `runIfAvailable` releases when work throws; N threads
  contending produce exactly one winner. The schema comes from the shipped changelog.

## Design decisions

- **A lease, not a lock.** A lock held by a dead process is held forever and nothing alerts on it; a lease
  expires.
- **Renewal checks `run_id` as well as `owner`.** A lease that expired, was taken and came back to the same
  owner string is lost, not held.
- **Release keeps the row.** One acquisition path (the `SKIP LOCKED` claim) instead of separate insert and
  update branches; the table is bounded by the number of lock names.
- **Micrometer is optional** and reached through the `RunLockListener` SPI, so the lock carries no
  observability dependency.
