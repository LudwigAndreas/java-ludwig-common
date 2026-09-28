# audit-spring-boot-starter

[English] | [Русский](README.ru.md)

Everything the platform's audit trail needs that a database, a scheduler or an outbox can provide: the
append-only `audit_event` table and its JPA sink, the outbox sink that ships the trail off-platform
exactly once, the per-category retention purge that audits itself, and the QueryDSL read side.

The envelope, the sink SPI and the redaction package are in [`audit-core`](../audit-core), which
depends on nothing in this repository. **Read that README first** - it explains why there are two
modules, what an `AuditEvent` is, and why there used to be nine of everything.

## Why this is a separate module

`security-spring-boot-starter`, `rest-client-spring-boot-starter` and `hot-reload-spring-boot-starter`
all need to audit and all sit below `db-core`. Maven's reactor DAG ignores scope, so an edge from the
audit envelope to `db-core` would be a cycle the moment anything upstream of `db-core` wanted a trail.
Those three take `audit-core` and get the SLF4J sink; anything that already has a data source can take
this one on top and get a table as well.

| Module | Takes | Why |
|---|---|---|
| security, rest-client, hot-reload | `audit-core` | Below `db-core` |
| export, file-ingest, outbox, reconciliation | `audit-core` | The destination is the deployment's choice, not the module's |
| user-settings | `audit-spring-boot-starter` | Its trail was already persistent, so it needs the table, the changelog and the migration of its own rows |

## What is in it

| Piece | What it is |
|---|---|
| `AuditEventEntity` | One flat, append-only row per event; a `SnapshotEntity`, so `db-core`'s `SnapshotImmutabilityListener` rejects every update |
| `JpaAuditSink` | Writes `audit_event` in the **caller's** transaction (`Propagation.SUPPORTS`) |
| `OutboxAuditSink` | Ships events through `outbox-spring-boot-starter`; the event id is the idempotency key |
| `CategoryFilteringAuditSink` | An allow-list in front of the outbox sink, for the reason below |
| `AuditEventQueryRepository` / `AuditTrailQuery` | The read side, QueryDSL only, capped at 1000 rows |
| `AuditRetentionPurge` | A `job-core` self-scheduling job under a leased `RunLock`, per category |
| `ActorResolverAuditorProvider` | Feeds `db-core`'s `created_by` stamping from the trail's own actor resolution |
| `AuditMetrics` | `ludwig.audit.events.written`, `.sink.failures`, `.events.purged` |
| `AuditProblemMapper` | An unwritable audit row becomes a **503**, not a 500 |

## The table

One flat table for every category, not one shaped table per subsystem. The reasoning is
`SyncAuditRecord`'s, which had already made the same call inside one module:

> The question this table exists to answer is "what happened around this, in order, on this day" - and
> answering it across five tables means five queries and a manual merge, at the moment somebody is
> trying to work out why a partner was called twice.

This change is that argument at the next scale up: five shapes in one module became nine across the
platform.

**Append-only, enforced.** `AuditEventEntity` extends `db-core`'s `SnapshotEntity`, reusing the
platform's existing immutability mechanism rather than inventing a second one. There is no update path
in this module and no delete path outside the retention purge, and an attempt to update a row throws
rather than a convention asking nicely.

`UserSettingAuditEntity` explicitly argued *against* that base class - "that family models data
imported from an outer system and carries the provenance columns to match, none of which mean anything
for a record this service authored itself". That was true of a table one service wrote for itself and
stops being true here, because this table receives events from more than one writer: `source_system`
records which deployment wrote the row, and `imported_at` records when it was written as opposed to
when the thing happened. The two differ for an event relayed from another service, and only
`occurred_at` has meaning to an auditor. `source_version` and `source_timestamp` are genuinely unused
and stay null.

**No foreign keys, to anything, ever.** Two reasons, both of which the outbox's and reconciliation's
history tables already gave: an FK forces a key-share lock on the parent row on every insert,
contending with the work being audited, and the trail has to outlive the rows it describes.

`attributes` is `jsonb` and deliberately **not** GIN-indexed. The attributes differ per action and are
read as a whole rather than filtered on, so a child table of name/value pairs would turn every read
into a join and every write into N inserts on the path of the work being audited - and a GIN index
would cost write throughput on every audited operation to speed up a query an auditor runs monthly. A
deployment that genuinely needs one can add it without this module's participation.

## Transactions, and why `SUPPORTS`

`JpaAuditSink` is `Propagation.SUPPORTS`, so a write inside a business transaction commits with it and a
write outside one gets the repository's own. That is the property worth having, and
`SettingsAuditRecorder` already had it and said why:

> An audit trail written in a separate transaction is one that can record a change that was rolled
> back, or miss one that was not, and either makes the whole trail something an auditor has to qualify
> rather than rely on.

Not `REQUIRES_NEW`, the tempting alternative: a separate transaction survives the caller's rollback, so
the trail would record changes that never happened. Not `MANDATORY` either, because the observational
categories genuinely have no ambient transaction - an authorization denial happens before any business
method is entered.

The consequence to expect: for a mutation category this sink's failure marks the caller's transaction
rollback-only, which is exactly why `FAIL_OPERATION` is the right policy there. Swallowing it would
leave the caller committing a transaction the driver has already doomed.

## Shipping the trail off-platform

`OutboxAuditSink` is the answer to "a single place an auditor can be pointed at" when that place is a
SIEM. Through the outbox and not straight onto a broker, for the reason the outbox exists and
`OutboxReportEventPublisher` already argued about `report.ready`: the event and the change it describes
are written in one transaction, so there is no window in which something happened and the SIEM will
never be told, and none in which the SIEM is told about a change that rolled back.

`AuditEvent.id` is both the ordering key and the idempotency key. As the idempotency key it makes a
redelivery recognisable as the same event rather than as a second thing that happened - the difference
between an audit trail and a count of broker retries. As the ordering key it is per *event* rather than
per actor, because audit events are independent facts and keying them by actor would serialise a busy
administrator's whole trail behind one partition for no ordering anybody needs.

**It is off by default and allow-listed by category**, because `OutboxEventPublisher` is
`Propagation.MANDATORY`. Shipping a category whose events are emitted outside a transaction - an
authorization denial, an outbound call record - would turn every one of those events into an
`IllegalTransactionStateException` on the business path. `ludwig.audit.outbox.categories` defaults to
`settings` and `export`, the two that always have one. An allow-list rather than a deny-list so that a
category added to the platform later does not start being shipped to somebody's SIEM because nobody
thought to exclude it.

## Retention

Modelled on `ExportRetentionPurge`: a `job-core` `SelfSchedulingLifecycle` under a leased `RunLock`, so
every replica runs the schedule and exactly one does the work, and an instance that dies mid-purge does
not stop the next one for good. It owns its own `TaskScheduler`, so it runs whether or not the service
remembered `@EnableScheduling`.

Defaults are measured in **years** - seven - because retention here is a compliance figure and not a
disk-space one. Per category, because an access-denial record and a consent decision do not have the
same legal basis.

**The purge audits itself.** Every pass that removed anything writes an `audit.purged` event saying
which category, what cutoff, and how many rows. An audit trail that can be silently shortened is not a
trail: without that row, "nothing happened in that window" and "something removed the window" are the
same observation. The event is emitted *after* the batch commits and carries the count actually
removed, and is `PARTIAL` when the batch did not reach everything that had expired.

Bounded batches, for the reason `UserSettingAuditQueryRepository` gave: a single unbounded delete over a
year of rows takes a long lock and a large amount of WAL, on a table that is also on the write path of
every audited operation.

> **Without `job-core` on the classpath, nothing purges.** The purge is behind
> `@ConditionalOnClass(RunLock.class)` so that a deployment wanting only the trail is not made to add a
> scheduler and a lock table. The trade-off is real and it is the deployment's to notice: an audit table
> that grows forever is a disclosure risk with nobody watching it.

## Migration of the two legacy trails

Three persistent audit stores existed before this module. Two migrate, one deliberately stays.

| Was | Disposition |
|---|---|
| `user_setting_audit` | **Migrated** by `audit-002`; its `[redacted]` marker rewritten by `audit-004` |
| `sync_audit_record` | **Migrated** by `audit-003` |
| `outbox_status_history` | **Stays.** See below |

Both migrations move the **rows**, not just the schema. A consolidation that starts the new trail empty
and leaves the history in tables nobody queries has moved the auditor's problem rather than solved it.
The integration test asserts that the migrated count reconciles with the source count, which is the
thing that would otherwise fail silently.

Mapping notes worth knowing:

- `user_setting_audit.subject` becomes `on_behalf_of` and `actor` becomes `actor_subject`. That is the
  direction the old columns actually meant - `subject` was whose setting changed, not who changed it -
  and an administrator editing somebody else's profile is what makes it visible.
- `sync_audit_record.category` (the `RUN`/`RECORD`/`JOB`/`LEASE`/`OPERATOR` enum) becomes an
  **attribute**, not `audit_event.category`. That column names the subsystem and these five name a kind
  of event within it; collapsing them would make one column mean two different things depending on the
  row.
- Nothing in this repository ever *wrote* `sync_audit_record`: the entity and its repository existed and
  the `PersistingReconciliationAuditLogger` its javadoc referred to did not. The migration runs anyway,
  because a deployment may have written rows through a logger of its own and a migration that assumed
  the table was empty would silently discard them.
- Both changesets are guarded by a `tableExists` precondition with `onFail="MARK_RAN"`, so a service
  using neither module is not broken by them. That guard has a consequence: **a changeset marked as ran
  is never reconsidered**, so this changelog has to run *after* the modules whose tables it reads.
  `AuditLiquibaseAutoConfiguration` names them in `@AutoConfigureAfter` by class name string, since this
  module must not depend on either - `user-settings-spring-boot-starter` depends on *it*.
- The legacy tables are **not dropped**. A deployment verifies the migration against them and drops them
  itself; a changeset that deleted the only copy of the evidence would be the wrong default.

### Why `outbox_status_history` stays

It is the one place in this repository where two audit stores is the right answer, and the reason is
written into `PersistingOutboxAuditLogger` as well as here, because without it somebody will eventually
"finish the job".

`user_setting_audit` and `sync_audit_record` were audit trails and nothing else.
`outbox_status_history` is **operational state the dispatcher itself reads** - attempt counts, last
failure, when a message was last tried - and folding it into a generic table would couple dispatch to
audit retention, so a deployment shortening its audit window would shorten the dispatcher's memory with
it. It *additionally* forwards every transition to the platform trail, so "who was never told what
happened" is still answerable without knowing the outbox's schema.

## Configuration

Every property is under `ludwig.audit` and bound by `audit-core`'s `AuditProperties`. The blocks this
module implements:

```yaml
ludwig:
  audit:
    jpa:
      enabled: true                  # the audit_event table
    outbox:
      enabled: false                 # shipping to a SIEM is a data decision
      categories: [ settings, export ]
    retention:
      enabled: true
      default-period: P7Y
      by-category:
        access: P2Y
      interval: PT6H
      batch-size: 500
      drain-timeout: PT30S
    liquibase:
      enabled: true                  # off to fold the changelog into your own master
```

## Correlation

`audit-core` ships `CorrelationProvider.none()`, because binding to
`observability-spring-boot-starter` or `web-core` from a module with no in-repo dependencies is
impossible and binding from this one would make an optional dependency mandatory in practice. A service
with either publishes three lines:

```java
@Bean
CorrelationProvider auditCorrelationProvider(CorrelationContext context) {
    return context::currentCorrelationId;
}
```

Worth the indirection because of what the id is for: it is the only thing that joins an audit event to
the logs, traces and downstream calls of the same request. "Who changed this" is answerable without it;
"what else happened in that request" is not.

## Reading the trail

`AuditEventQueryRepository` with an `AuditTrailQuery` criteria object, QueryDSL only, capped at
`AuditTrailQuery.MAX_LIMIT` (1000) rows per read. A criteria object rather than a row of nullable
parameters, because the questions an auditor actually asks are combinations - "everything this person did
last Tuesday", "every denied access to this resource this month" - and one finder method per combination is
how a read side becomes twenty methods nobody can tell apart.

The cap is hard rather than configurable. A caller that needs the whole trail wants an export, not a page of
a hundred thousand rows assembled in the heap of a service that is also serving requests - and a deployment
that could raise it would eventually raise it during the incident when the trail mattered most.

**No HTTP endpoint ships.** That is a decision, not an omission: who may read an audit trail is the single
most consequential authorization question in the whole subsystem, and it differs per deployment - a
compliance role, a break-glass procedure, a separate internal-only service. A starter that mounted a
`GET /admin/audit` behind a guessed role would either be too open somewhere or quietly unused everywhere.
A service that wants one injects the repository and mounts its own, under its own policy.

## Not in scope, as a decision

**Hash-chaining or signing for tamper evidence.** A real feature, deliberately not half-built: it needs
key management and a verification tool to mean anything, and half of it - a chain nobody verifies - is
worse than none, because it looks like a guarantee. What is claimed is enforced append-only, which is a
smaller promise that is actually kept.
