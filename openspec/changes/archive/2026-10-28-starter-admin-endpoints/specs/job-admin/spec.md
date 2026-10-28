## Purpose

Shows which scheduled jobs a service runs and who holds each job's lease, and lets an operator run a job now or
free a lease whose holder is gone, reporting cluster-wide truth rather than the state of whichever replica answered.

## ADDED Requirements

### Requirement: Jobs and leases are readable from the lease table

`job-core` SHALL declare `jobs.read`, `jobs.operate` and `jobs.privileged`, and serve under `jobs.read`:

- `GET /admin/v1/jobs` - every scheduled job registered in this service, with its schedule and its lease (owner,
  acquired-at, expires-at) as recorded in the lease table;
- `GET /admin/v1/jobs/locks` - every lease row, including leases taken by components that are not scheduled jobs.

Whether a job is executing SHALL be reported only from the lease table, never from the answering replica's memory.

#### Scenario: Which replica runs the outbox poller
- **WHEN** an operator lists the jobs
- **THEN** each job shows the lease owner currently recorded, the same answer from whichever replica served it

### Requirement: A job can be run now

`POST /admin/v1/jobs/{name}/run` (`jobs.operate`) SHALL attempt the job's run-now on the answering replica,
asynchronously, and answer `202` when the run was started. When the job's lease is held by an owner (on any replica)
the response SHALL be `409` naming the holder. An unknown job SHALL be `404`.

#### Scenario: Run now while another replica holds the lease
- **WHEN** the lease for `outbox-poller` is held by another replica
- **THEN** the response is `409` naming that owner, and no run starts

### Requirement: A lease can be force-released

`POST /admin/v1/jobs/locks/{name}/release` (`jobs.privileged`, reason required) SHALL release the named lease as the
run-lock capability's forced release defines, and answer with the lease as it was before release.

#### Scenario: A lease held by a dead pod
- **WHEN** an administrator releases the lease of a job whose holder pod no longer exists
- **THEN** the lease has no owner, the next scheduled attempt on any replica may acquire it, and the audit event
  records the former owner and the reason
