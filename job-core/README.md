# job-core

[English] | [Русский](README.ru.md)

The mechanics every scheduled, database-backed worker in this platform is built from, in one place so
that there is **one of each** rather than one per module.

This module has no jobs of its own. It exists because
[`outbox-spring-boot-starter`](../outbox-spring-boot-starter) and
[`reconciliation-spring-boot-starter`](../reconciliation-spring-boot-starter) are the same machine
pointed in opposite directions - one claims local rows and pushes state out, the other claims local
rows and pulls state in - and everything between "wake up" and "record the outcome" is identical.
Two divergent copies of a backoff curve is the specific bug this module prevents: it surfaces months
later as "the other service recovers from a partner outage differently from this one", with nothing
to point at.

## What is in it

| Piece | What it is |
|---|---|
| `BackoffPolicy` / `BackoffCalculator` | Exponential backoff with a multiplier, a cap and jitter, computed for a persisted `next_attempt_at` rather than slept on in-call |
| `ScheduleSpec` / `SelfSchedulingLifecycle` / `ScheduledJob` | A `SmartLifecycle` base that schedules itself on an injected `TaskScheduler`, refuses to run twice at once, contains exceptions, and drains on shutdown |
| `SkipLockedClaim` | The `UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING *` statement, rendered and executed |
| `ClaimOwner` / `JobInstanceIdentity` | The string this process writes into `locked_by` / `owner` columns |
| `RunLock` / `JdbcRunLock` | A leased, cluster-wide mutual exclusion for a named unit of scheduled work |

## Backoff

```java
BackoffCalculator backoff = new BackoffCalculator(
        new BackoffPolicy(Duration.ofSeconds(2), 2.0, Duration.ofMinutes(10), 0.3));

message.setNextAttemptAt(Instant.now().plus(backoff.nextDelay(message.getAttempts())));
```

`attempt` is the 1-based total attempt count **after** the failure that just occurred, so the first
failure passes `1` and gets `initialInterval`.

resilience4j-core is used only as an interval function, never as `@Retry` or
`Retry.decorateSupplier`. Those retry *in-call*, holding a thread and a database connection while
they sleep; everything here instead writes the next due time to a row and lets the next tick pick it
up, which is what survives the pod being rescheduled mid-backoff.

**Jitter is not decoration.** Without it, every record that failed because a partner was down for one
minute comes due again at exactly the same instant, on every instance at once - and the partner,
which has just come back, is knocked over by the entire backlog and fails again. A `jitter` of `0`
makes the curve deterministic, which is what the growth-curve tests use and what a test asserting
exact due times needs; production values belong somewhere around `0.2`-`0.3`.

## Scheduling

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

Or, when the body already lives in a service bean, `new ScheduledJob(name, scheduler, spec,
drainTimeout, service::pollOnce)`.

Four properties of the base class are load-bearing:

- **Not `@Scheduled`.** That annotation takes its interval as an attribute, which means either a
  placeholder string re-parsed from configuration already bound to a `Duration`, or SpEL - and
  either way it requires the *consuming application* to have enabled `@EnableScheduling`. A starter
  that silently does nothing when the service forgets is not plug-and-play, and the symptom is a
  backlog with no error anywhere.
- **Fixed delay, never fixed rate.** A fixed rate keeps firing while a run is still going, so a job
  that starts taking longer than its interval accumulates overlapping runs until the pool is
  exhausted - precisely when the system is already under stress. A fixed delay degrades by running
  less often, which is visible and harmless.
- **Non-reentrant.** A cron schedule does not prevent overlap, and neither does an actuator "run
  now" arriving mid-run. A second entry is a logged no-op, not a second concurrent run of claiming
  logic that assumes one runner per instance.
- **Drains on stop.** `stop()` cancels *without interrupting* and then waits up to `drainTimeout`.
  Interrupting instead abandons rows mid-claim: they stay marked in-progress, owned by a process that
  no longer exists, recoverable only once a stale reclaimer notices minutes later. Keep
  `drainTimeout` comfortably below the container runtime's termination grace period, or the pod is
  killed mid-drain and nothing is gained.

`runNow()` shares the non-reentrancy guard and returns whether it actually ran - that is what an
actuator "trigger a run" operation should call and report.

## Claiming

```java
List<OutboxMessage> claimed = SkipLockedClaim.claim(entityManager, OutboxMessage.class,
        "outbox_message",
        "status = 'PROCESSING', locked_at = now(), locked_by = :lockOwner",
        "t.status IN ('PENDING', 'FAILED') AND t.next_attempt_at <= :now",
        "t.created_at, t.id",
        batchSize,
        Map.of("lockOwner", lockOwner, "now", Instant.now()));
```

renders and runs

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

Three things about that statement are not stylistic:

- **The locking clause is on the subquery, not the `UPDATE`.** Postgres cannot attach one to an
  `UPDATE`, and the row locks an update takes are *blocking*. The subquery is what makes a second
  poller step over rows the first is claiming instead of queueing behind them - the difference
  between N instances sharing the work and N instances taking turns.
- **`RETURNING *`, not a second `SELECT`.** One round trip, and no window in which a row is claimed
  but not yet loaded. It is also why this executes as a result-set-returning query: an
  `executeUpdate()` discards the returned rows.
- **A total `ORDER BY`.** Without one, Postgres may return due rows in any order and a backlog can
  starve its own oldest entries indefinitely. The symptom - "most things are fine, a few records are
  days stale" - is very hard to attribute to a missing sort.

Callers pass fragments they wrote themselves and bind every value as a parameter; the table name and
the fragments come from module code, never from configuration or a request. There are two overloads
because JPA binds `:name` and plain JDBC binds `?`, and having one rewrite the other's placeholders
is how a string literal eventually gets mangled.

## The run lock

```java
Optional<RunLockHandle> held = runLock.tryAcquire("billing-status", Duration.ofMinutes(5));
if (held.isEmpty()) {
    return;                       // another instance has this run; come back next tick
}
try (RunLockHandle lease = held.get()) {
    // ... periodically: if (!lease.renew(Duration.ofMinutes(5))) { stop immediately; }
}
```

A **lease**, not a lock. A lock held by a process that has died is a lock held forever, and "the job
silently stopped running after a pod was killed" is not something anything alerts on: the schedule
keeps firing, every tick declines, and the backlog grows behind a metric nobody is watching. Every
acquisition therefore expires, and a holder that wants to keep it has to keep saying so.

Two consequences callers must design for:

1. **Holding a lease is never proof that you still hold it.** `renew` returns whether it is still
   yours. A holder whose renewal fails has been superseded and must stop *immediately*, not finish
   the batch - another instance is already redoing this run.
2. **`tryAcquire` never blocks.** A scheduled job that cannot get the lease has nothing to wait for,
   and blocking would pin a scheduler thread for as long as another instance's run takes.

Renewal is conditional on `run_id` as well as `owner`. A lease that expired, was taken by another
instance and came back to this one is **lost**, not still held - the owner string would match, and
the work this process is part-way through is no longer the authoritative run.

Every lease operation runs on its own short auto-commit JDBC connection rather than through the
caller's `EntityManager`. Enlisting it in the caller's transaction would make it invisible to
everyone else until that transaction commits, and would roll it back along with a failed run - so a
run that failed would also silently give up a lease it needed to hold long enough to record why.

## Schema (PostgreSQL only)

One table, `job_run_lock`, shipped as `db/changelog/job-core/job-core-changelog.xml` and applied by
`JobCoreLiquibaseAutoConfiguration` as an independent `SpringLiquibase` bean alongside the
application's own. Set `ludwig.job-core.liquibase.enabled=false` to fold it into your own master
changelog instead.

`expires_at` is the single source of truth for "is this lease held": a holder that stops renewing
stops being the holder, with no participation from the holder required.

## Configuration (`ludwig.job-core.*`)

| Property | Default | Meaning |
|---|---|---|
| `ludwig.job-core.enabled` | `true` | Master switch for this module's autoconfiguration |
| `ludwig.job-core.owner` | hostname + random suffix | Identity written into lock-owner columns |
| `ludwig.job-core.liquibase.enabled` | `true` | Whether this module applies its own changelog |

Deliberately tiny. Everything that varies per job - schedules, batch sizes, retry budgets - is
configured by the module that owns the job, under that module's own prefix. `BackoffPolicy` and
`ScheduleSpec` are value types rather than `@ConfigurationProperties` classes for exactly this
reason: binding them here would force every consumer onto one property prefix.

The instance identity is **not** stable across restarts and must not be treated as if it were. A
restarted pod is a new owner; adopting work left behind by the previous one is an explicit reclaim
step, never an accident of the two sharing a name.

## Consuming it

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>job-core</artifactId>
</dependency>
```

Versions come from `ludwig-bom`; name none here. The only runtime dependencies are Spring Context,
Spring Boot autoconfigure, the Jakarta Persistence API (compile-scope, for the JPA flavour of the
claim helper - this module maps no entities and imposes no entity scanning), resilience4j-core and
SLF4J. Liquibase is optional.

## Testing

`mvn test` runs the unit suite: the backoff growth curve and its jitter window, `ScheduleSpec`
validation, and the lifecycle's non-reentrancy, exception containment and drain-on-stop behaviour.
`JdbcRunLock` is exercised against a real Postgres by its consumers' integration tests, where
competing instances are what the lease actually has to survive.
