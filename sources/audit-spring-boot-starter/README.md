# audit-spring-boot-starter

***English** · [Русский](README.ru.md)*

The persistent half of the platform audit trail: the append-only `audit_event` table and its JPA sink,
an outbox sink that ships events to a SIEM exactly once, a per-category retention purge that audits its
own deletions, and a QueryDSL read side. The envelope, sink SPI, failure policy and redaction are in
[`audit-core`](../audit-core) - read that README first.

| | |
|---|---|
| Role | starter |
| Depends on (in-repo) | [`audit-core`](../audit-core), [`db-core`](../db-core), [`job-core`](../job-core), [`outbox-spring-boot-starter`](../outbox-spring-boot-starter), [`web-core-spring-boot-starter`](../web-core-spring-boot-starter) |
| Used by | `user-settings-spring-boot-starter` |
| Surfaces | none (no HTTP endpoint by design); optional Kafka via outbox |

Which modules take what:

| Module | Takes | Why |
|---|---|---|
| security, rest-client, hot-reload | `audit-core` | Below `db-core`; get the SLF4J sink |
| export, file-ingest, outbox, reconciliation | `audit-core` | The destination is the deployment's choice |
| user-settings | this starter | Its trail must be persistent and transactional |
| a service | this starter | To get a queryable trail for all of the above |

| Piece | What it is |
|---|---|
| `AuditEventEntity` | One flat, append-only row per event (`SnapshotEntity`: `db-core`'s `SnapshotImmutabilityListener` rejects updates) |
| `JpaAuditSink` | Writes `audit_event` in the caller's transaction (`Propagation.SUPPORTS`) |
| `OutboxAuditSink` | Ships through the outbox; the event id is the idempotency and ordering key |
| `CategoryFilteringAuditSink` | Allow-list in front of the outbox sink |
| `AuditEventQueryRepository` / `AuditTrailQuery` | Read side, QueryDSL, max 1000 rows per read |
| `AuditRetentionPurge` | Per-category purge on `job-core` under a leased `RunLock` |
| `ActorResolverAuditorProvider` | Feeds `db-core`'s `created_by` stamping from the trail's actor resolution |
| `AuditMetrics` | Written / failed / purged counters |
| `AuditProblemMapper` | An unwritable audit row becomes **503**, not 500 |

## Usage

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>audit-spring-boot-starter</artifactId>
</dependency>
```

With the dependency on the classpath, every `AuditSink.record(...)` in the service also writes a row to
`audit_event`, in the same transaction as the business change when there is one.

### Transactions

`JpaAuditSink` uses `Propagation.SUPPORTS`: inside a business transaction the row commits or rolls back
with the change; outside one (an authorization denial before any business method) it gets the
repository's own transaction.

- Not `REQUIRES_NEW`: the trail would record changes that rolled back.
- Not `MANDATORY`: observational events have no ambient transaction.

For a mutation category, a failed insert marks the caller's transaction rollback-only, which is why
`settings` uses `FAIL_OPERATION` (see audit-core). The failure surfaces as **503 Service Unavailable**.

### Reading the trail

```java
List<AuditEventEntity> rows = auditEventQueryRepository.find(AuditTrailQuery.builder()
        .actorSubject("u-123")
        .from(Instant.parse("2026-10-20T00:00:00Z"))
        .to(Instant.parse("2026-10-21T00:00:00Z"))
        .limit(500)
        .build());
```

A criteria object, QueryDSL only, capped at `AuditTrailQuery.MAX_LIMIT` (1000) rows - a hard cap: a
caller needing the whole trail needs an export, not a heap full of rows.

**No HTTP endpoint ships.** Who may read the audit trail is the most consequential authorization decision
in the subsystem and differs per deployment (compliance role, break-glass, separate internal service). A
service that wants an endpoint injects the repository and mounts its own, under its own policy.

### Shipping to a SIEM

`OutboxAuditSink` writes each event to the outbox in the same transaction as the change it describes, so
the SIEM is never told about a rolled-back change and never misses a committed one. The outbox event type
is `audit.event` (payload `AuditEvent`), declared as an `OutboxEventTypes` bean so it appears in the
AsyncAPI document. Route it with `ludwig.outbox.routes.*` like any other event.

- `AuditEvent.id` is the **idempotency key** (redelivery is recognisable) and the **ordering key** (per
  event, so a busy administrator's trail is not serialised onto one partition).
- **Off by default, allow-listed by category.** The outbox requires a transaction; categories emitted
  outside one (access denials, outbound calls) would fail on the business path. Defaults: `settings`,
  `export`. An allow-list, so a new category is never shipped to a SIEM by accident.

### Correlation

To record correlation ids, publish a `CorrelationProvider` bean (see
[audit-core](../audit-core#correlation)).

## Configuration

All properties are bound by `audit-core`'s `AuditProperties`. Blocks implemented here:

| Property | Default | Meaning |
|---|---|---|
| `ludwig.audit.jpa.enabled` | `true` | Write the `audit_event` table |
| `ludwig.audit.outbox.enabled` | `false` | Ship events through the outbox |
| `ludwig.audit.outbox.categories` | `settings`, `export` | Categories shipped (allow-list) |
| `ludwig.audit.retention.enabled` | `true` | Run the retention purge (needs `job-core`) |
| `ludwig.audit.retention.default-period` | `P7Y` | Retention for categories not listed |
| `ludwig.audit.retention.by-category.<category>` | none | Per-category retention, e.g. `access: P2Y` |
| `ludwig.audit.retention.interval` | `PT6H` | Purge interval |
| `ludwig.audit.retention.batch-size` | `500` | Rows per delete batch |
| `ludwig.audit.retention.drain-timeout` | `PT30S` | Shutdown wait for a running purge |
| `ludwig.audit.liquibase.enabled` | `true` | Apply the changelog as an own `SpringLiquibase` bean |

Retention is a compliance figure, set per category, because an access-denial record and a consent decision
have different legal bases. Agree values with the compliance owner before changing them.

## Deployment

**Schema (PostgreSQL).** `db/changelog/audit/audit-changelog.xml`:

| Changeset | Effect |
|---|---|
| `audit-0001` | Creates `audit_event` with indexes on `(actor_subject, occurred_at)`, `(category, occurred_at)`, `occurred_at`, `(resource_type, resource_id)`, `correlation_id` |
| `audit-0002` | If `user_setting_audit` exists: copies its rows into `audit_event` (`subject` → `on_behalf_of`, `actor` → `actor_subject`) |
| `audit-0003` | If `sync_audit_record` exists: copies its rows (its `category` becomes an attribute) |
| `audit-0004` | Rewrites the `[redacted]` marker in migrated rows to `***REDACTED***` |

`audit-0002`/`0003` are guarded by `tableExists` preconditions with `onFail="MARK_RAN"`; a changeset marked
as ran is never reconsidered, so this changelog runs after the user-settings and reconciliation changelogs
(`@AutoConfigureAfter`). The source tables are **not dropped**: verify the copied row counts, then drop
them yourself.

**Table design.**

| Column | Meaning |
|---|---|
| `occurred_at` | When it happened - the time an auditor uses |
| `imported_at` | When the row was written (differs for relayed events) |
| `source_system` | Which deployment wrote the row (`ludwig.audit.source-system`) |
| `category`, `action`, `outcome`, `reason` | What happened and how it ended |
| `actor_subject`, `actor_type`, `actor_display_name`, `on_behalf_of` | Who acted, for whom |
| `resource_type`, `resource_id`, `resource_name` | What was touched |
| `correlation_id`, `trace_id` | Join keys to logs and traces |
| `attributes` (`jsonb`) | Module-specific details, already redacted; not GIN-indexed (read whole, rarely filtered) |

No foreign keys: an FK would lock the parent row on every insert, and the trail must outlive the rows it
describes. `source_version` and `source_timestamp` are unused and stay null.

**Without `job-core` on the classpath nothing is purged** (the purge is `@ConditionalOnClass(RunLock.class)`).
An audit table that grows forever is a disclosure risk; check that the purge runs.

## Observability

**Metrics:**

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `ludwig.audit.events.written` | counter | `category`, `outcome` | Events recorded |
| `ludwig.audit.sink.failures` | counter | `category`, `policy` | Sink failures, under either policy |
| `ludwig.audit.events.purged` | counter | `category` (`all` for the default period) | Rows removed by retention |

**Logs.** `INFO Audit retention purge removed <n> event(s) from category <c> older than <cutoff>
(<expired> expired)`; `WARN Could not release the audit retention purge lock`.

**Recommended alerts:**

| Condition | Why |
|---|---|
| `increase(ludwig_audit_sink_failures_total[5m]) > 0` | Events are missing from the trail (or operations are failing, for `FAIL_OPERATION` categories) |
| `ludwig_audit_events_purged_total` never increases while retention is enabled (check weekly) | Purge not running: missing `job-core`, or lock stuck |
| `audit_event` table size growth (database monitoring) | Capacity planning; the table is on the write path of every audited operation |

**Known gaps:** no gauge for the oldest row age per category, which would show directly whether retention
is keeping up.

## Audit

The purge records itself, so "nothing happened in that window" and "something removed the window" are
distinguishable:

| Field | Value |
|---|---|
| category | `audit` |
| action | `audit.purged` |
| resource | the purged category (`audit-event` for the default period) |
| outcome | `SUCCESS`, or `PARTIAL` when the batch did not reach every expired row |
| attributes | category, cutoff, removed, expired |

The event is emitted after the batch commits and carries the count actually removed.

## Runbook

| Symptom | Likely cause | What to do |
|---|---|---|
| Settings changes return 503 | `audit_event` insert fails (schema, disk, permissions) | Check the database error in the log; fix and retry. The changes were rolled back, nothing to clean up |
| `ludwig_audit_sink_failures_total` increases for `LOG_AND_CONTINUE` categories | Same causes | Fix the sink. Events in the failure window exist only in the SLF4J log (if enabled); re-import from logs if the trail must be complete |
| Audit table keeps growing past retention | Purge not running | Check the startup log for `AuditRetentionPurge`, `job-core` on the classpath, `ludwig.audit.retention.enabled`, and the lock: `SELECT * FROM job_run_lock WHERE lock_name = 'ludwig-audit-retention-purge';` |
| Purge outcome `PARTIAL` repeatedly | Batch too small for the expiry rate | Increase `retention.batch-size` or decrease `retention.interval` |
| SIEM receives nothing | `outbox.enabled=false`, category not in the allow-list, or no outbox route for `audit.event` | Check config; see the outbox runbook for delivery |
| Startup fails with `IllegalTransactionStateException` on audit | A non-transactional category added to `outbox.categories` | Remove it from the allow-list |
| Investigate a person's actions | - | `SELECT occurred_at, category, action, outcome, resource_type, resource_id FROM audit_event WHERE actor_subject = '<id>' AND occurred_at BETWEEN '<from>' AND '<to>' ORDER BY occurred_at;` |
| Investigate a request | - | `SELECT * FROM audit_event WHERE correlation_id = '<id>' ORDER BY occurred_at;` |

## Testing

`AuditStoreIntegrationTest` (Docker, PostgreSQL): JPA sink in and out of transactions, rollback together
with the business change, immutability, the read side and its cap, retention per category with the
self-audit event, the outbox sink and its allow-list, and the legacy-table migrations with row counts
reconciled against the source (`LegacyAuditTablesInitializer` creates the source tables).

## Design decisions

- **One flat table for every category.** "What happened around this, in order" is one query, not a merge
  across per-subsystem tables.
- **Append-only, enforced** by `SnapshotEntity` immutability, not by convention.
- **`outbox_status_history` stays separate.** It is operational state the outbox dispatcher reads;
  merging it would tie dispatch to audit retention. Its transitions are additionally recorded here.
- **No tamper-evidence.** Append-only is the promise that is actually kept; see audit-core.
