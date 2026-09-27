# The leased run lock

## Purpose
`job-core`'s `RunLock` is the platform's only distributed lock: the way a scheduled job says
"this runs on exactly one replica". It is a **lease**, not a lock, because a lock held by a
process that has died is a lock held forever, and "the job silently stopped running after a pod
was killed" is not something anything alerts on.

## Requirements

### Requirement: One distributed lock for the platform
Anything needing single-replica execution SHALL take it from `job-core`'s `RunLock`. A module
SHALL NOT declare a lock interface or a lock table of its own.

#### Scenario: A module needs single-replica execution
- **WHEN** a module has work that must run on one replica — a digest collapsing many rows into
  one message, a retention purge, a compaction over an expiry boundary
- **THEN** it takes `RunLock` from `job-core`, rather than adding an interface, a table and a
  fencing rule of its own as `notification-service` once did

#### Scenario: Work is already partitioned by its own data access
- **WHEN** a poller built on `SELECT … FOR UPDATE SKIP LOCKED` already hands disjoint batches to
  every replica
- **THEN** it does **not** take the lock. Wrapping one around it throws away all but one
  replica's throughput to solve a problem that does not exist

### Requirement: Acquisition never blocks and declining is a normal outcome
`tryAcquire` SHALL return immediately with an empty `Optional` when the lease is held elsewhere.
`runIfAvailable` SHALL return `false`.

#### Scenario: Two of three replicas tick at the same time
- **WHEN** three replicas run the same scheduled job and one holds the lease
- **THEN** the other two return `false` immediately and do nothing. This is the expected steady
  state, not an error to log or alert on

#### Scenario: A scheduled job cannot get the lease
- **WHEN** a tick finds the lease held
- **THEN** it returns rather than waiting. Blocking would pin a scheduler thread for as long as
  another instance's run takes, and a scheduled job has nothing to wait for

### Requirement: Holding a lease is never proof of still holding it
A holder SHALL call `renew` periodically, and SHALL stop **immediately** when `renew` returns
false — not after finishing the current batch.

#### Scenario: A renewal fails mid-run
- **WHEN** `handle.renew(ttl)` returns false
- **THEN** the holder stops at once, because another instance has already been granted the lease
  and is redoing this run. Finishing the batch means the work is done twice

#### Scenario: A lease expires, is taken by another instance, and returns to the original owner
- **WHEN** the original owner calls `renew` after its lease lapsed and was reacquired by it
- **THEN** renewal fails, because renewal is conditional on `run_id` as well as `owner`. The
  owner string would match, but the run this process is part-way through is no longer the
  authoritative one

### Requirement: The lease is released even when the work throws
`runIfAvailable` SHALL release the lease in a `finally`, and SHALL let the exception propagate
unchanged.

#### Scenario: The guarded work throws
- **WHEN** the callback passed to `runIfAvailable` throws
- **THEN** the lease is released and the exception propagates. Letting it expire would also be
  correct but would block the next scheduled run for a full lease period after a failure that
  took milliseconds; and whether a failed run should stop the schedule is the caller's decision,
  not the lock's

### Requirement: Lease operations run outside the caller's transaction
Every lease operation SHALL run on its own short auto-commit JDBC connection, not through the
caller's `EntityManager`.

#### Scenario: A run fails inside a transaction that also touched the lease
- **WHEN** a guarded run fails and its transaction rolls back
- **THEN** the lease is unaffected. Enlisting it in the caller's transaction would make the
  acquisition invisible to other replicas until commit, and would roll back a lease the failing
  run still needs long enough to record why it failed

#### Scenario: A consumer has no JPA
- **WHEN** a module takes `RunLock` without a `PlatformTransactionManager`, a JPA entity or
  entity scanning
- **THEN** it works, because lease operations need none of them

### Requirement: Release nulls the owner rather than deleting the row
A release SHALL null the owner and move `expires_at` to `now()`.

#### Scenario: A lock is acquired and released many times
- **WHEN** a job runs on a schedule for a long period
- **THEN** the lock table stays bounded by the number of distinct lock names rather than
  churning a row per run, and acquisition keeps exactly one shape — the `SKIP LOCKED` claim —
  instead of an insert branch and an update branch that have to agree

### Requirement: Lock metrics are optional and the owner is never a tag
Instrumentation SHALL go through the no-op-by-default `RunLockListener` SPI, bound to Micrometer
only when a `MeterRegistry` is present. The owner SHALL NOT be a meter tag.

#### Scenario: A consumer has no Micrometer
- **WHEN** a module takes `job-core` without `micrometer-core`, or with the jar but no registry
  bean
- **THEN** the lock works unchanged and carries no observability dependency

#### Scenario: A run is superseded while still working
- **WHEN** a renewal finds the lease no longer held
- **THEN** `ludwig.job.lock.lost` is incremented, tagged by lock name only. This is the meter to
  alert on: some work has been done twice or abandoned half done. The owner carries a random
  suffix and is unbounded in cardinality across restarts, so it belongs in a log line
