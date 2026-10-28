# file-ingest-spring-boot-starter

***English** · [Русский](README.ru.md)*

The scheduled bulk file ingest: reads a large object out of storage, parses it record by record, writes the records
into the database and reports completion - **without ever holding the file in memory and without ever losing a
record**. A service writes a `RecordParser<R>` (bytes → records) and a `RecordApplier<R>` (records → staging rows);
schedule, lock, discovery, arrival detection, ranged streaming, checkpointing, batching, quarantine, the balance
check, receipts, archival, metrics, audit and the `fileingest` administrative area are the module's.

| | |
|---|---|
| Role | starter |
| Depends on (in-repo) | [`audit-core`](../audit-core), [`db-core`](../db-core), [`job-core`](../job-core), [`object-storage`](../object-storage-spring-boot-starter), [`outbox`](../outbox-spring-boot-starter) (optional at runtime), [`web-core`](../web-core-spring-boot-starter) |
| Used by | none in this repository (services importing partner files) |
| Surfaces | object storage (read, receipt, archive); administrative area `/admin/v1/fileingest`; optional outbox event `ingest.completed` |

**Not reconciliation.** [`reconciliation`](../reconciliation-spring-boot-starter) is demand-driven (local rows ask for
external state by key); a file is the authority and its keys are unknown until read. Reconciliation's per-record
bookkeeping is right for thousands of drifting records and wrong for ten million rows a day.

## Usage

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>file-ingest-spring-boot-starter</artifactId>
</dependency>
```

Object storage is required; for S3 add the AWS SDK artifacts listed in
[object-storage](../object-storage-spring-boot-starter#usage). Declare a parser and an applier bean for each configured
task (the validator refuses a task without a bean and a bean without a task).

### The loop

```
discover object  →  confirm arrival  →  claim run (unique on bucket+key+etag)
    │
    └─ loop:  open(uri, Range(checkpoint, EOF))
              → decompress → split into records
              → accumulate a batch, bounded by BOTH record count AND bytes
              → ONE transaction: { write batch to staging ; advance checkpoint ; quarantine rows ; read counts }
              → renew the lock lease
    │
    └─ final: balance check → single set-based MERGE staging → target
              → run = SUCCEEDED (+ ingest.completed via outbox)  →  receipt object  →  archive source
```

### Guarantees and how they are kept

| Guarantee | Mechanism |
|---|---|
| **No duplicates, no gaps on crash** | The batch, the checkpoint, quarantine rows and read counts commit in **one transaction** (`BatchCommitter.commit`). Data-then-checkpoint duplicates; checkpoint-then-data leaves a silent gap. `CheckpointAtomicityIT` fails if the transaction is split |
| **Bounded memory** | Batches flush at `max-records` **or** `max-bytes`. A record larger than `max-bytes` is quarantined as `OVERSIZED`. ArchUnit forbids `readAllBytes`/`readAllLines`/`Files.readString`; `LargeFileHeapIT` ingests 200 MB under a heap ceiling |
| **Exactly once per content** | Unique constraint on `(bucket, key, etag)` in `file_ingest_run` (`versionId` preferred where the bucket is versioned). A corrected re-upload under the same name has a new etag and is a new run |
| **Never read a half-written file** | `arrival.sentinel` (default): wait for `{name}.done`. Or `arrival.stability-window`: identical size + etag across two polls. Configuring neither logs a warning |
| **Target untouched unless balanced** | Before the merge: `read == applied + quarantined + skipped`, `read == expected` when the sentinel declares a count, and `COUNT(*)` of staging = rows written. A failed balance fails the run with all numbers in the message |
| **Atomic target change** | Staging table, then one set-based `MERGE` |
| **One replica per task** | `RunLock`, **renewed inside the batch loop**; a failed renewal stops the run immediately (cost: at most one batch) |
| **Bounded run time** | `schedule.run-timeout` enforced between batches; the checkpoint is kept |

**Checkpoints are format-dependent** (`Checkpoint` is sealed):

| Shape | For | Resume cost |
|---|---|---|
| `ByteOffset` | line-delimited (CSV, NDJSON) | ranged GET from the offset |
| `RecordOrdinal` | XML, Parquet, fixed-block, headered formats | re-read from the start and skip |

A byte offset counts **uncompressed** bytes, so a gzipped object's resume re-reads and discards (gzip cannot seek). Ask
large partners for a seekable format.

**Side-effect order** is fixed: `SUCCEEDED` in the database first, then the receipt, then the archive. A crash after the
status re-runs into an idempotent finish. A failing receipt or archive does not fail the run.

**Write path.** Records stream into a per-task staging table in the **service's** schema (the service owns its
columns). Writer `auto` uses PostgreSQL `COPY` when available, batched JDBC `INSERT` otherwise. SQL and JDBC are
confined to `ru.ludwigandreas.ingest.bulk` (`SqlConfinementTest`); identifiers are validated, values always bound.
The module's own tables are queried with QueryDSL. `truncate-staging-on-fresh-run` applies **only** when the
checkpoint is zero (truncating on a resume would lose the work the checkpoint claims).

**Quarantine.** A row carries the raw record (truncated at `quarantine.max-record-length`), byte offset, record ordinal,
stage (`PARSE` / `APPLY` / `OVERSIZED`), error and run id.

- `fail-fast`: first bad record fails the run.
- `threshold` (default): continue while the ratio stays under `max-ratio`; evaluated after at least 100 records.
  Catches the "file parses but every row is wrong" case (a new column, a changed delimiter).

**Receipts** (off by default): `receipt.key-template` (e.g. `processed/{name}.receipt.json`) with run id, source uri
and etag, read/applied/quarantined/skipped counts, bytes, timestamps - the counts let the partner check the numbers
themselves.

**Completion event** (`events.enabled`, requires outbox): `ingest.completed` (`IngestRunSummary`) in the same
transaction as `SUCCEEDED`; the run id is the ordering and idempotency key. Declared as an `OutboxEventTypes` bean.

**Run states** use web-core's `OperationStatus`; this module writes `RUNNING`, `SUCCEEDED` and `FAILED` only.

**Scale-out** of one object across replicas (byte-range splits) is not built; the checkpoint model, ranged reads and
append-only staging keep it possible (it would add a split id to `file_ingest_run` and sum the balance across splits).

## Configuration

```yaml
ludwig:
  ingest:
    enabled: true
    scheduler-enabled: true        # false on an instance that only serves the endpoint
    drain-timeout: 30s
    liquibase:
      enabled: true
    events:
      enabled: false               # requires outbox-spring-boot-starter
    endpoint:
      enabled: true
      recent-runs: 20
      allow-manual-run: false
    tasks:
      partner-catalogue:
        source:    { uri: s3://partner-drop/catalogue/, pattern: "catalogue-*.csv.gz",
                     compression: auto, max-objects-per-pass: 1 }
        arrival:   { sentinel: "{name}.done", stability-window: 0s,
                     expected-count-field: recordCount }
        schedule:  { cron: "0 30 6 * * *", run-timeout: 2h }
        lock:      { lease: 5m, renew-at-remaining-fraction: 0.3 }
        batch:     { max-records: 5000, max-bytes: 32MB }
        quarantine: { policy: threshold, max-ratio: 0.005, max-record-length: 8KB }
        receipt:   { enabled: true, key-template: "processed/{name}.receipt.json" }
        archive:   { mode: move, prefix: "processed/" }
        alert:     { expected-by: "07:00", zone: Europe/Moscow }
        write:     { staging-writer: auto, jdbc-batch-size: 1000,
                     truncate-staging-on-fresh-run: true }
```

| Property | Meaning |
|---|---|
| `ludwig.ingest.scheduler-enabled` | Run schedules on this instance |
| `ludwig.ingest.drain-timeout` | Shutdown wait for a running pass |
| `tasks.<t>.source.uri` / `pattern` / `compression` / `max-objects-per-pass` | What to read |
| `tasks.<t>.arrival.sentinel` / `stability-window` / `expected-count-field` | Arrival detection and declared count |
| `tasks.<t>.schedule.cron` / `run-timeout` | When, and the run budget |
| `tasks.<t>.lock.lease` / `renew-at-remaining-fraction` | Lease and renewal margin (a fraction, so changing the lease keeps the margin) |
| `tasks.<t>.batch.max-records` / `max-bytes` | Batch bounds |
| `tasks.<t>.quarantine.policy` / `max-ratio` / `max-record-length` | Quarantine behaviour |
| `tasks.<t>.receipt.*`, `archive.mode` / `prefix` | Post-completion side effects |
| `tasks.<t>.alert.expected-by` / `zone` | Deadline for `ludwig.ingest.missing` |
| `tasks.<t>.write.staging-writer` / `jdbc-batch-size` / `truncate-staging-on-fresh-run` | Staging write path |

**Startup refuses** (all in one message): a task without a bean or a bean without a task, an unparseable cron, a
`run-timeout` shorter than the lease, `max-record-length` larger than `batch.max-bytes`, a sentinel template that
makes every object wait on one marker, an unknown zone, an unparseable source uri, events without the outbox.

## Deployment

- **Schema** (`db/changelog/file-ingest/file-ingest-changelog.xml`, ids `file-ingest-NNNN` / author
  `ludwig-file-ingest`, own `SpringLiquibase` bean, `ludwig.ingest.liquibase.enabled`): `file_ingest_run` (identity
  `container` + `object_key` + `content_identity` with its unique constraint, status, checkpoint, counts, timings, lock owner, version, post-completion flags)
  and `file_ingest_quarantine`. No foreign key between them (separate retention decisions).
- **Staging tables** in the service's own changelog, one per task.
- **Bucket access**: read/list on the source prefix; put for receipts; copy/delete for archival.
- **Partner agreement**: a sentinel (`{name}.done`, optionally with a record count) is the strongest arrival signal.

## Observability

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `ludwig.ingest.runs` | timer | `task`, `status` (`succeeded`, `failed`) | Run duration and outcome |
| `ludwig.ingest.records` | counter | `task`, `outcome` (`read`, `applied`, `quarantined`, `skipped`) | Record accounting |
| `ludwig.ingest.bytes.read` | counter | `task` | Uncompressed bytes consumed |
| `ludwig.ingest.batch.flush` | timer | `task` | Batch flush (checkpoint included) |
| `ludwig.ingest.merge` | timer | `task` | Staging → target merge |
| `ludwig.ingest.skipped` | counter | `task`, `reason` | Objects already processed |
| **`ludwig.ingest.missing`** | gauge | `task` | **1 when no `SUCCEEDED` run exists today by `alert.expected-by`** |

`missing` is the signal for the file that never came: no run, no error, a quietly stale table. It counts only
`SUCCEEDED` runs, and every instance runs the monitor without a lock (so it cannot stop with a lock holder).

**Administrative area** `/admin/v1/fileingest`: recent runs with counts, `operation` envelope and **checkpoints** (a
moving checkpoint = a large file; a static one = a problem); starting a task's run is `fileingest.operate`.

**Logs.** `INFO`: configuration validated, schedules started, staging writer chosen, already-ingested skips, "not
reading yet" (arrival), manual trigger, receipt written, archive done. `WARN`: no completed run by the deadline, no
arrival mechanism configured, receipt or archive failure. `ERROR`: run failed.

**Recommended alerts:**

| Condition | Why |
|---|---|
| `ludwig_ingest_missing == 1` | Today's file has not been ingested |
| `increase(ludwig_ingest_runs_seconds_count{status="failed"}[1d]) > 0` | Balance, quarantine threshold, timeout or lease failure |
| `increase(ludwig_ingest_records_total{outcome="quarantined"}[1d])` above normal | Partner format drift |

**Known gaps:** no retention for `file_ingest_run` and `file_ingest_quarantine`; quarantine rows hold raw record text
(possibly personal data). Purge them on a schedule appropriate to the data.

## Audit

Category `ingest`; resource `ingest-task` / task name (the task outlives runs; the run id is an attribute).

| Action | Outcome |
|---|---|
| `ingest.claimed`, `ingest.resumed`, `ingest.batch-committed`, `ingest.merged`, `ingest.completed`, `ingest.skipped-already-processed` | `SUCCESS` |
| `ingest.quarantined` | `PARTIAL` |
| `ingest.failed`, `ingest.missing` | `FAILURE` |

"Did anything go wrong overnight" is a query on the outcome column.

## Administration

Operator endpoints are on the platform's administrative surface, `/admin/v1/{area}`, and exist only where `security-spring-boot-starter`'s surface does. Each is guarded by one operation; which of the service's roles hold it is the service's own `ludwig.admin.grants` (roles from the authority resolver, never token claims), and an operation granted to no role is refused to everyone. A `privileged` call needs an `Admin-Reason` header, and every call is audited under category `admin` with the operation id as its action. `GET /admin/v1/me/operations` lists what the caller holds.

| Operation | Endpoints | Allows |
|---|---|---|
| `fileingest.read` | `GET /admin/v1/fileingest/tasks`<br>`GET /admin/v1/fileingest/tasks/{task}`<br>`GET /admin/v1/fileingest/tasks/{task}/runs/{runId}` | See ingest tasks and their runs, with each run's checkpoint and counts. |
| `fileingest.operate` | `POST /admin/v1/fileingest/tasks/{task}/runs` | Trigger an ingest pass now. |
| `fileingest.privileged` | none in this release | No operation yet; reserved so that grants for this area stay the same across releases. |

**Removed:** the `fileingest` actuator endpoint and `ludwig.ingest.endpoint.*` (including `allow-manual-run`); starting a run is `fileingest.operate`. A run requested while one is in progress is `409`.

Grant block (the role names are the service's own):

```yaml
ludwig:
  admin:
    grants:
      "[fileingest.read]": [ORDERS_ADMIN, ORDERS_SUPPORT]
      "[fileingest.operate]": [ORDERS_ADMIN]
      "[fileingest.privileged]": [ORDERS_ADMIN]
```

## Runbook

| Symptom | Likely cause | What to do |
|---|---|---|
| `ludwig_ingest_missing == 1` | File not delivered, sentinel missing, pattern mismatch, or run failed | Check the bucket for the object and `{name}.done`; the log line `is not reading ... yet` names the reason; check the last run at `GET /admin/v1/fileingest/tasks/{task}` |
| Run `FAILED`: balance mismatch | Truncated file (declared count differs), staging writer lost rows, or parser bug | Message carries all four numbers; target is untouched. Ask the partner for the full file; re-upload (new etag) to rerun |
| Run `FAILED`: quarantine threshold | Partner changed format | `SELECT stage, error, count(*) FROM file_ingest_quarantine WHERE run_id = '<id>' GROUP BY 1, 2;` fix parser or ask partner |
| `RUNNING` with a static checkpoint | Stuck I/O or database | `SELECT id, checkpoint_position, records_read, locked_by, started_at FROM file_ingest_run WHERE status = 'RUNNING';` thread dump; the lease expires and the next pass resumes from the checkpoint |
| Run stopped at `run-timeout` | File grew or staging degraded | Raise `run-timeout` deliberately; the next run resumes |
| Corrected file not picked up | Same etag (filesystem store with preserved mtime) | Re-upload so the content identity changes |
| Re-ingest a file deliberately | - | Delete or rename the identity row only if you are sure: `SELECT id, status, content_identity FROM file_ingest_run WHERE task = '<task>' AND object_key = '<key>';` - prefer re-uploading the object |
| Receipt/archive warnings | Bucket permissions | Fix permissions; the next pass re-applies idempotently |

## Testing

`mvn test`: batch bounding at both limits, balance arithmetic, quarantine threshold at and past the ratio, checkpoint
arithmetic, location templates, `COPY` escaping, identifier validation, shared and module ArchUnit rules (SQL/JDBC
confinement, no materialisation, published-api boundary, no `@RestControllerAdvice`).

`mvn verify` (Docker; PostgreSQL and LocalStack, digest-pinned, started once per suite):

| Test | Proves |
|---|---|
| `LargeFileHeapIT` | 200 MB ingests under a measured heap ceiling |
| `CheckpointAtomicityIT` | batch and checkpoint roll back together (fails if the transaction is split) |
| `CheckpointResumeIT` | a killed run resumes exactly once, transfers less than the whole object, with a ranged GET at the committed offset |
| `FileIngestLifecycleIT` | quarantine with ordinal and offset; threshold failure leaves the target untouched; re-offer skipped; same name new etag = new run; every success balances |
| `SentinelArrivalIT` | no sentinel = not read; sentinel not ingested; count mismatch fails with target untouched |
| `ConcurrentReplicaIT` | two replicas → one run, one set of staged rows |
| `SchedulingAndMissingFileIT` | schedules start; `missing` goes 1 past the deadline and back to 0 |
| `RunTimeoutIT` | a run past `run-timeout` stops between batches, keeping the checkpoint |
| `ReceiptAndArchiveIT` | receipt counts, archive, crash between `SUCCEEDED` and archive finishes idempotently |

## Design decisions

- **Checkpoint and data in one transaction** - the whole "never lose data" argument; do not refactor it apart.
- **Identity by content, enforced by a constraint**, not a flag.
- **Balance before merge**: a check after the merge would only report damage.
- **Sentinel by default**: inferring completion is weaker than the partner asserting it.
- **Auto-configuration in `ru.ludwigandreas.ingest.autoconfigure`**, separate from `config` (properties and validator),
  to avoid a package cycle.
- **SQL carve-out confined to one package** for `COPY` and set-based merge, which QueryDSL cannot express.
