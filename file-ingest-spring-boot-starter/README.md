# file-ingest-spring-boot-starter

[English] | [Русский](README.ru.md)

The once-a-day job that reads a large object out of storage, parses it record by record, writes the
records into the database, and tells somebody it's done — **without ever holding the file in memory
and without ever losing a record.**

A service writes two things: a `RecordParser<R>` and a `RecordApplier<R>`. Everything else is
configuration.

| | |
|---|---|
| **the author writes** | bytes → records, and records → staging rows |
| **the module owns** | schedule, lock, discovery, arrival detection, ranged streaming, checkpointing, batching, quarantine, the balance check, receipts, archival, metrics, audit, actuator |

## Why this is not part of `reconciliation-spring-boot-starter`

The two look similar and are inverses, and merging them would break this one.

[`reconciliation`](../reconciliation-spring-boot-starter) is **demand-driven**: stage 2 asks "which
*local* rows need external state" and stage 3 fetches *by key*. A file inverts that — **the file is
the authority**, and the keys are unknown until the file is read. `Fetcher.Paged` joins its results
against demand, so a record in the file with no matching local row would be **silently dropped**,
which is precisely the failure this module exists to prevent.

The sizing is opposite too. Reconciliation's per-record `sync_inbox_record` staging row carries
`attempts`, `next_attempt_at`, `locked_by` and `version` — exactly right for a few thousand drifting
records that each need their own retry budget, and exactly wrong for ten million rows a day, where
the per-row bookkeeping would cost more than the import.

`Fetcher` is `sealed` on purpose. There is no fifth shape to add.

## The core loop

```
discover object  →  confirm arrival  →  claim run (unique on bucket+key+etag)
    │
    └─ loop:  open(uri, Range(checkpoint, EOF))
              → decompress → split into records
              → accumulate a batch, bounded by BOTH record count AND bytes
              → ONE transaction: { write batch to staging ; advance checkpoint }
              → renew the lock lease
    │
    └─ final: single set-based MERGE staging → target
              → balance check
              → run = COMPLETED  →  receipt object  →  archive source
```

### 1. The checkpoint and the data commit in the same transaction

This is the entire "never miss data" argument, and it lives in one method: `BatchCommitter.commit`.

There is **no** arrangement of two transactions that is safe:

* **Data first, then checkpoint.** A crash in between leaves staged rows the checkpoint does not
  account for. The next run resumes from the older checkpoint and writes them again — *duplicates*.
* **Checkpoint first, then data.** A crash in between leaves a checkpoint claiming records that were
  never staged. The next run resumes past them and they are never read again — *a gap*: silent,
  permanent, and invisible to every count the module keeps, because the records were never read and
  so were never counted.

One transaction has neither failure mode. **This is exactly the kind of thing that gets refactored
apart by somebody who doesn't know why it's together** — for testability, for a cleaner service
layer, to reuse the writer elsewhere.

`CheckpointAtomicityIT` is the test that catches that, and it is the only one that does:
`CheckpointResumeIT` would pass with the transaction removed, because every write would still happen
on its own auto-commit. The atomicity test writes the batch, fails, and asserts that *both* the staged
rows and the checkpoint went away. It was verified to fail when the annotation is removed — an
architecture test that has never been seen red is a test nobody should trust.

Quarantine rows are in the same transaction, for the same reason: a quarantined record is accounted
for by the checkpoint just as an applied one is.

> The read counts (`records_read`, `bytes_read`) are in that transaction too, and getting this wrong
> was a real bug caught by the balance check during this module's own development: kept outside, they
> were lost when a run was interrupted while the applied count — which was inside — survived, so the
> resumed run read the remainder and the two stopped adding up.

### 2. Checkpoint granularity is format-dependent

`Checkpoint` is `sealed` over two shapes, and the parser declares which one it can produce:

| Shape | For | Resume costs |
|---|---|---|
| `ByteOffset` | line-delimited (CSV, NDJSON) | a ranged GET from the offset — nothing already consumed is transferred again |
| `RecordOrdinal` | XML, Parquet, fixed-block, anything with a header | re-read from the start and skip forward |

Both are equally **correct**. They differ only in what a resume costs, and saying so matters: an
ordinal checkpoint is not a degraded checkpoint, it is one whose resume re-reads rather than seeks.

A byte-offset-only design would have forced the first XML feed to be a rewrite of the loop rather
than an addition to it.

**One honest caveat about compression.** A byte offset counts *uncompressed* bytes, because that is
what the parser sees. For an uncompressed object those are the store's own bytes and the resume is a
ranged GET. For a **gzipped** object they are not, and there is no general way to map one to the
other — gzip is a single stream, and reaching an offset means decompressing everything before it. So
a gzipped resume re-reads and discards. That is a real cost, it is stated rather than hidden, and the
answer for a partner whose files are large enough for it to matter is to ask for a seekable format,
not to pretend the offset means something it does not.

### 3. Memory is bounded in bytes, not records

The batch flushes at `max-records` **or** `max-bytes`, whichever comes first. A record-count bound on
its own is a module that works until a partner sends one row with a 200 MB free-text column: five
thousand records is then a gigabyte, and the process dies with an `OutOfMemoryError` naming none of
the four million rows in the file.

A single record larger than `max-bytes` is a **poison record** — quarantined with its offset, never
grown into.

Nothing here calls `readAllBytes`, `readAllLines` or `Files.readString`, and no collection grows with
the object's size. An ArchUnit rule forbids the first three by name; the rule cannot catch a
collection that quietly accumulates across batches, so `LargeFileHeapIT` ingests a **200 MB object
under a measured heap ceiling**. The requirement is worth testing, not restating.

### 4. Identity is `(bucket, key, etag)` — and the constraint is the guard

`file_ingest_run` carries a unique constraint on that triple, and **that constraint is the
exactly-once guard** — not a `processed` flag somebody remembers to check. The difference is not
stylistic: a flag is read and *then* acted on, so two replicas reading it in the same moment both
act, while a unique constraint is enforced at the moment of the insert.

The third column keeps it honest. Keying on the object key alone means a partner re-uploading a
**corrected** file under the same name is recognised as a duplicate and skipped: silent data loss,
discovered weeks later when somebody asks why the correction never landed. Use `versionId` instead of
`etag` where the bucket is versioned — `StoredObject.contentIdentity()` prefers it automatically.

### 5. Arrival detection — the file must be complete before you read it

Reading a half-written object is **the most common way a daily ingest loses data**, and it is the
worst-behaved failure here because it does not look like one: the run reads what is there, parses it,
applies it, balances perfectly against what it read, and completes. The tail is simply absent, and
nothing alerts on a successful run. Every other guard in this module reasons about the bytes that
*were* there, so none of them can see it.

Two mechanisms, independently configurable, because partners differ:

* **`arrival.sentinel`** (default **on**): don't touch `data.csv` until `data.csv.done` exists. The
  strong form — the partner asserts completion rather than this module inferring it. The sentinel's
  content is ignored except for an optional record count (see the balance check).
* **`arrival.stability`**: no sentinel available, so require identical size + ETag across two polls
  `stability-window` apart, and require that long since the last write. Honestly weaker — an upload
  that stalls for longer than the window looks finished — which is why it is not the default.

Configuring neither logs a warning at startup. It is legitimate for a partner who writes atomically
and a mistake otherwise, and the module says so rather than deciding for you.

### 6. The done file, both directions

"Send done file" covers two separate features. Both are built and switch independently:

* **consume** a partner's sentinel — `arrival.sentinel`, above, on by default;
* **produce** a receipt object after a successful run — off by default, because a partner who does
  not read receipts should not have objects written into their bucket. `receipt.key-template`
  (e.g. `processed/{name}.receipt.json`), content is the run summary: run id, source uri + etag,
  records read / applied / quarantined / skipped, byte count, started/finished timestamps.

The receipt carries the **counts**, not just "ok", because the question a partner actually asks is
whether the number of records that arrived is the number they sent — and the counts let them check
that themselves, without asking anybody.

### 7. The ordering of the side effects cannot be changed

`run = COMPLETED` in the database **first**, because it is the source of truth. Then the receipt.
Then the archive.

A crash anywhere after the status re-runs, finds the run already complete through the identity
constraint, and redoes only the bucket operations — which are idempotent: copying an object over
itself and deleting something already absent both succeed.

The reverse order is the one that looks natural and cannot recover: archive the source, then mark the
run, and a crash in between leaves a bucket that says "processed" and a database that says the run
never happened. The next pass finds no object and no record that there ever was one.

A failing receipt or archive does **not** fail the run. The records are already in the target;
turning a bucket permission problem into a failed run would make the module claim that data did not
arrive when it did.

### 8. Write path: staging table, then one set-based merge

Records stream into a per-task staging table; the target is changed once, at the end, by one
statement. That buys three things a row-by-row merge loses:

* **the target changes atomically** — nothing ever observes a half-imported catalogue;
* **the parse phase runs free of the target's constraints** — one violating row does not abort a
  transaction carrying five thousand good ones;
* **the counts are checkable**, which is what the balance check is built on.

> ⚠️ **This needed a deliberate exception to a standing repo rule.** `CLAUDE.md` mandates QueryDSL
> against generated Q-types only — no JPQL, no SQL strings — and neither Postgres `COPY` (via
> `CopyManager`, which is *why* you want this: it streams and is several times faster than batched
> `INSERT`) nor a set-based upsert can be expressed in QueryDSL. The exception is granted with
> conditions, all four of which are met:
>
> 1. every SQL string is in **one package**, `ru.ludwigandreas.ingest.bulk`, and nowhere else;
> 2. each statement carries javadoc saying why QueryDSL cannot express it — the standard
>    `DistributedLockRepository`'s native `@Query` already meets;
> 3. `SqlConfinementTest` fails the build if SQL *or* JDBC appears outside that package, so the
>    exception cannot quietly spread;
> 4. `CLAUDE.md`'s "Module conventions" records the carve-out and its boundary.
>
> This module's **own** tables are queried through QueryDSL predicates in `IngestRunQueries`, like
> everything else in the platform. The carve-out is about the bulk path into the author's schema.

A JDBC-batch writer is provided for non-Postgres, selected by `write.staging-writer` — whose default
asks the data source once, at startup, and picks `COPY` when it can. The module is fast where it can
be and correct everywhere, and is not Postgres-only by accident.

Identifiers (table and column names) are interpolated after validation, because no SQL dialect lets a
placeholder stand for one. **Values are always bound as parameters.**

### 9. The balance check is the correctness proof

A run may only reach `COMPLETED` when:

```
records_read == records_applied + records_quarantined + records_skipped
```

and, when the sentinel declared an expected count, `records_read == expected`. There is a third check:
a `COUNT(*)` of the staging table must match what the run believes it wrote — the equation reconciles
the engine's numbers with each other, and only counting the table catches a staging writer that
reported five thousand rows and wrote four thousand nine hundred.

A run that cannot balance is `FAILED`, loudly, with all four numbers in the message, **and the target
is untouched** because the balance runs *before* the merge. Checking afterwards would make it a report
on damage already done.

This is the difference between a module that claims not to lose data and one that demonstrates it
every single run. It found two real bugs in this module's own engine before release.

The sentinel's count is the stronger of the two checks: the equation is satisfied perfectly by a run
that read a *truncated* file, because everything it read was accounted for. The expected count is the
only number that comes from outside the run.

### 10. Poison records never abort and never vanish

A quarantine row carries the raw record text (truncated at `quarantine.max-record-length`), the byte
offset **and** the record ordinal, the stage (`PARSE` / `APPLY` / `OVERSIZED`), the error, and the run
id. All of it: a row with only a count sends whoever reads it back to the source object with a line
number they do not have, and the first question about a quarantine spike is whether it is the
partner's file or this service's constraints.

Two policies:

* **`fail-fast`** — the first bad record fails the run. Right for a file that is *entirely* wrong,
  where dying on row one is the fastest possible signal; wrong for one bad row in four million.
* **`threshold`** (**default**) — continue while the rate stays under `max-quarantine-ratio`, fail
  past it.

The threshold is what catches the case the other two handle badly and which happens most: a partner
adds a column, or changes a delimiter, and the file **parses**. Every row produces a record and every
record is wrong. Nothing about it is malformed enough for fail-fast to trigger on row one, and nothing
is rare enough to be junk — but the rate goes from a handful of rows a day to most of the file.

The ratio is not applied until at least 100 records have been read. A first bad record gives a ratio
of 1.0, which is past every threshold, and failing there would be fail-fast wearing the wrong name.

### 11. Schedule, lock, lease renewal

`job-core`'s `SelfSchedulingLifecycle` + `ScheduleSpec.cron`, and the platform's one leased `RunLock`
via `runIfAvailable`. `ExportRetentionPurge` is the reference, and the consolidated lock has landed,
so there is no second lock anywhere here.

**The lease is renewed inside the batch loop.** A forty-minute ingest under a five-minute lease
renewed only at the top has lost the lock by minute six — nothing tells it so, a lease simply
expires — and from minute six a second replica is free to import the same object into the same
staging table. The result is every record written twice, discovered when somebody notices the target
has roughly double the rows it should.

**A failed renewal stops the run immediately, mid-file.** The asymmetry is enormous: stopping costs
the work since the last checkpoint, which is at most one batch, because the next run resumes from
exactly where this one committed. Continuing risks every remaining record being written twice. There
is no amount of remaining work that makes the second trade worth taking.

Renewal happens *before* the margin runs out — `lock.renew-at-remaining-fraction`, a fraction rather
than a duration so that changing the lease does not silently change the safety margin.

`schedule.run-timeout` is checked in the same place and is enforced, not merely validated. The socket
timeout is per-read and catches a store that sends nothing; it cannot catch a run that is making
progress and will not finish — a file that grew tenfold, a staging table whose indexes have degraded —
because such a run reads a byte often enough to keep every lower-level timeout satisfied while running
into the next business day. Stopping keeps the checkpoint, so a budget slightly too small does not make
a large file permanently un-ingestable; it makes the operator raise the budget deliberately.

### 12. Observability — including the file that never came

Metrics interface + Micrometer implementation + no-op, following `OutboxMetrics` / `ExportMetrics` /
`ReconciliationMetrics`:

| Metric | What |
|---|---|
| `ludwig.ingest.bytes.read` | uncompressed bytes consumed |
| `ludwig.ingest.records{outcome}` | read / applied / quarantined / skipped |
| `ludwig.ingest.batch.flush` | per-batch flush duration, checkpoint included |
| `ludwig.ingest.merge` | staging → target merge duration |
| `ludwig.ingest.runs{status}` | run duration and outcome by task |
| `ludwig.ingest.skipped{reason}` | objects already processed |
| **`ludwig.ingest.missing`** | **non-zero when no file has arrived for a task by its `alert.expected-by` time** |

The last one is the one worth alerting on. Every other number can look perfectly healthy while the
thing the module exists to do is not happening: for a once-a-day job the failure nobody notices is the
file that never showed up. The schedule fires, the listing is empty, a line goes to DEBUG, and the
pass exits successfully — the run count stays flat because there was no run, the error count stays at
zero because there was no error, and the table goes quietly stale until somebody downstream asks why
a number looks wrong.

Same reasoning as the run-lock lease: **an absence nothing reports is an absence nobody sees.**

It checks for a `COMPLETED` run, not any run — a run that started and failed is not a file that
arrived, and counting it would silence the alarm on exactly the morning it should ring. Every instance
runs the monitor and there is no lock on it, deliberately: putting the one signal about things *not
happening* behind a lock means it stops when the lock holder dies.

Plus an audit SPI (`IngestAuditLogger`, SLF4J default) and a `fileingest` actuator endpoint listing
recent runs, their counts and **their checkpoints** — because the question at nine in the morning is
usually "is it moving", and only the checkpoint answers it. A run `RUNNING` for forty minutes with a
climbing checkpoint is a large file; the same run with a checkpoint that hasn't moved in ten is a
problem, and from outside the process the two are otherwise indistinguishable.

### 13. Publishing completion

Optional dependency on `outbox-spring-boot-starter`. When present and `events.enabled`, a completed
run publishes `ingest.completed` carrying the run summary — through the outbox, so the event and the
`COMPLETED` status commit together. `OutboxReportEventPublisher` in export is the pattern, including
the run id being both the ordering key and the idempotency key.

Ordering, because a later event about the same run has to arrive after this one. Idempotency, because
the outbox delivers at least once and a downstream service reacting twice to a four-million-row import
is worse than one that missed it — the second is noticed.

Enabling events without the outbox on the classpath is refused at startup rather than silently
discarding them.

### 14. Scale-out stays possible

Nothing here forecloses splitting one object into N byte ranges across replicas later — each worker
skipping to the first record boundary after its start and reading past its end to finish the last
record, the standard input-split trick. It is **not built**. What matters is that the checkpoint model
(a position plus a record count, per run), the ranged read API, and a staging table that is append-only
during the parse phase do not make it impossible, because "supports any size in future" concretely
means this.

The pieces that would need to change: `file_ingest_run` would gain a split identifier alongside the
identity triple, and the balance check would sum across splits instead of over one run.

## Configuration (`ludwig.ingest.*`)

```yaml
ludwig:
  ingest:
    enabled: true
    scheduler-enabled: true        # false on an instance that only serves the endpoint
    drain-timeout: 30s
    liquibase:
      enabled: true                # false to fold the changelog into your own master history
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

`truncate-staging-on-fresh-run` applies **only** when the checkpoint is at zero. A resumed run must
not truncate: those staged rows are exactly the work its checkpoint says has been done, and removing
them would lose every record before the resume point while the checkpoint went on claiming they were
written. That is the one way this module could lose data with no individual step being wrong, which is
why the decision is taken from the checkpoint rather than from configuration.

The startup validator refuses a configured task with no bean, a bean with no configured task, an
unparseable cron, a `run-timeout` shorter than the lock lease, a `max-record-length` larger than
`batch.max-bytes`, a sentinel template that would make every object wait on one marker, an unknown
time zone, an unparseable source uri, and events enabled with no outbox. All of it in one message.

## Schema

`db/changelog/file-ingest/file-ingest-changelog.xml`, changeset ids and author namespaced
(`ingest-NNN` / `ludwig-file-ingest`), applied by its own `FileIngestLiquibaseAutoConfiguration` —
which copies `JobCoreLiquibaseAutoConfiguration` exactly, including `@AutoConfigureAfter(
LiquibaseAutoConfiguration.class)` and the guard-by-**name** (a type guard makes the bean suppress
itself). Gate with `ludwig.ingest.liquibase.enabled`.

Two tables: `file_ingest_run` (the identity triple and its unique constraint, status, checkpoint, all
the counts, timings, lock owner, version, the two post-completion flags) and
`file_ingest_quarantine`. **Per-task staging tables are not here** — they belong to the author's
schema, because only the author knows what a record looks like, and a module that created them would
be guessing at columns.

There is no foreign key from quarantine to run, deliberately: a cascade would make the retention of
the two a single decision, and they are not one — a run row is small and worth keeping a long time, a
quarantine row can be 8 KB of raw record.

## Consuming it

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>file-ingest-spring-boot-starter</artifactId>
</dependency>
```

Versions come from `ludwig-bom`; name none here. `object-storage-spring-boot-starter` is **not**
optional — reading the object through the platform's one `ObjectStore` rather than a bucket client of
this module's own is most of the point — so a service that wants S3 also declares the AWS SDK
artifacts that starter documents.

### One note on the layout

The auto-configuration classes are in `ru.ludwigandreas.ingest.autoconfigure`, not in `config`, which
is this module's one departure from the other starters. It is a correctness fix, not a preference:
with the wiring and the properties in one package, `config` creates the engine's beans and the engine
reads `config`'s properties — a package cycle the shared `cycles` rule correctly refused. Splitting
them leaves `config` holding the bound properties and the startup validator, and nothing in the module
depends on `autoconfigure` at all. The rule library is *told* about the package rather than having the
rule suppressed, so bean definitions are still required to be in one place.

## Testing

`mvn test` runs the unit suite and the architecture rules: batch bounding at both limits, the balance
arithmetic in both directions, quarantine threshold behaviour at *and* past the ratio, checkpoint
arithmetic for both sealed variants, every location-derivation template, the `COPY` escaping, and the
identifier check. Plus the 42 shared ArchUnit rules and five module-specific ones — the SQL and JDBC
confinement, the no-materialisation rule, the published-api boundary, and the absence of a
`@RestControllerAdvice`.

`mvn verify` adds the integration suite against Postgres **and** LocalStack, both digest-pinned
(LocalStack rather than MinIO for the reason
[`object-storage-spring-boot-starter`](../object-storage-spring-boot-starter/README.md#why-localstack-and-not-minio)
documents: MinIO's images can no longer be pulled anonymously, so there is no digest to pin):

| Test | What it proves |
|---|---|
| `LargeFileHeapIT` | a 200 MB object ingests fully **under a measured heap ceiling** |
| `CheckpointAtomicityIT` | the batch and the checkpoint **roll back together** — the one test that catches the transaction being split, verified to fail without it |
| `CheckpointResumeIT` | a run killed mid-file resumes and the target holds every record exactly once; the resume transfers **less than the whole object**; and it issues a **ranged** GET starting at the committed offset |
| `FileIngestLifecycleIT` | a corrupt record mid-file is quarantined with the right ordinal and byte offset while everything else lands; a rate past the threshold fails the run and leaves the target untouched; a re-offered object is skipped; the same *name* with a different etag is a new run; every `COMPLETED` run balances |
| `SentinelArrivalIT` | an object with no sentinel is **not read at all**; the sentinel is not itself ingested; a declared count that does not match fails the run with the target untouched |
| `ConcurrentReplicaIT` | two replicas racing the same object produce exactly one run and one set of staged rows |
| `SchedulingAndMissingFileIT` | one schedule per enabled task plus the monitor, all started — and `ludwig.ingest.missing` going to 1 past the deadline with nothing ingested, and back to 0 once something lands |
| `RunTimeoutIT` | a run past `schedule.run-timeout` stops between batches and keeps the checkpoint it reached |
| `ReceiptAndArchiveIT` | the receipt carries the counts and the source is archived; a crash between `COMPLETED` and the archive re-runs into a clean idempotent finish |

The resume tests assert on the **bytes transferred and the ranges requested**, not on the fact that
the run finished. A run that re-read the object from zero and skipped forward would finish correctly
and would have thrown away the entire reason `ObjectStore` has a ranged read; asserting resumption
rather than proving it is how that regression gets in.

Containers are started once for the suite rather than per class. `@Container` stops a static container
when its test class finishes, so the second IT class gets new containers on new ports while Spring —
whose context cache key has not changed — reuses the context wired to the old ones. The symptom is
"connection refused" in the second class and nowhere else, a long way from its cause.
