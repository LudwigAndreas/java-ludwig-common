## Context

The change stays inside `outbox-spring-boot-starter`, but it changes a shared contract (`outbox-admin`), adds a
migration and deletes data by default. That is why it has a design.

The module already has everything the job needs:
- `job-core`, for `SelfSchedulingLifecycle`, `ScheduleSpec` and `RunLock`;
- `audit-core`, for `AuditSink` and `AuditCategories.OUTBOX`;
- `OutboxMetrics`, with a Micrometer and a no-op implementation.

`AuditRetentionPurge` (`audit-spring-boot-starter`) and `ExportRetentionPurge` (`export-spring-boot-starter`) are
the platform's existing purge shape, and this job copies it rather than inventing another.

## Goals / Non-Goals

**Goals:**
- Bounded growth.
- A deletion is never silent.
- A retried message is never deleted out from under an operator.

**Non-Goals:** see the proposal. No new admin area or operation: job administration already covers run-now.

## Decisions

1. **`finished_at` column over deriving the age from history.** `outbox_status_history` is optional
   (`audit.persist-history=false` by default), so the history cannot be the clock. `published_at` exists only for
   `PUBLISHED`.

   The migration (`0006-finished-at.sql`):
   - adds the column;
   - backfills `published_at` for `PUBLISHED` rows and `now()` for `DEAD_LETTER` and `DISCARDED` rows, so a
     pre-existing failure gets a full period before anyone can delete it;
   - adds a partial index `(status, finished_at) WHERE finished_at IS NOT NULL`.

   The column is written in three places, each a one-line edit beside the existing `setStatus`:
   - `OutboxOutcomeRecorder` (published, dead-lettered);
   - `OutboxAdminService.discard`;
   - `OutboxAdminService.retry`, which clears it.
2. **The delete is a single statement per batch, declared `@Transactional` on the repository method.** It is
   `DELETE ... WHERE id IN (SELECT id ... ORDER BY finished_at LIMIT :batch FOR UPDATE SKIP LOCKED)`, preceded in
   the same transaction by the history delete for those ids. The transaction sits on the repository because of
   the self-invocation trap that `AuditRetentionPurge`'s javadoc records. `SKIP LOCKED` keeps the purge from
   waiting on a row the admin surface is retrying.
3. **Per-status passes.** There is one pass for `PUBLISHED` with its period, and one each for `DEAD_LETTER` and
   `DISCARDED` with the terminal period. Each pass repeats batches until one returns fewer than `batch-size`, or
   until the lease is half spent; whatever is left waits for the next run. Each pass emits its own audit event, so
   the audit trail says per status what was removed.
4. **Audit after commit, outcome `PARTIAL` when expired rows remain.** This copies `AuditRetentionPurge` exactly.
   The event is recorded through `AuditSink` with category `outbox`, resource type `outbox-message`, and attributes
   `status`, `cutoff` and `count`.
5. **Validation at bind time.** A `@Validated` `Retention` nested class on `OutboxProperties` checks that both
   periods are positive and that `published` is at least `P1D`. The `P1D` floor exists because idempotency
   deduplicates against live rows only, so the published retention is the dedup window. One day is a floor, not a
   recommendation, and the README says so.
6. **Registration.** The job is a bean in `OutboxAutoConfiguration` with
   `@ConditionalOnProperty(ludwig.outbox.retention.enabled, matchIfMissing = true)` and `@ConditionalOnBean(RunLock)`.
   Without a `RunLock` (no DataSource for job-core's lock), the job is not registered and a startup WARN says
   retention is inactive. Running unlocked on every replica would double the deletes' lock contention.

**Dependency-direction check.** Does `outbox-spring-boot-starter` gain an in-repo dependency? No. It already
depends on `job-core` and `audit-core`; no edge is added, so no cycle is possible.

**POMs.** No POM changes: not the root `pom.xml`, `ludwig-bom` or `ludwig-service-parent`.

**Mechanical checks.** No new convention. The migration is covered by `scripts/check_migrations.sh`, which runs as
part of the gate.

## Risks / Trade-offs

- [A duplicate published after the retention period is re-sent.] → The `P1D` floor, plus a README paragraph
  stating that `retention.published` is the idempotency window.
- [The first run after upgrade deletes a large `PUBLISHED` backlog.] → Bounded batches with per-batch commits, a
  lease-bounded run, and `PARTIAL` audit outcomes until the backlog is cleared.
- [Rows deleted by default surprise an upgrading deployment.] → **BREAKING** in the CHANGELOG, with the one property
  that restores the old behaviour.

## Migration Plan

Liquibase applies `0006` on start (or the service includes it in its own changelog when
`ludwig.outbox.liquibase.enabled=false`). Rollback: the changeset has a `--rollback ALTER TABLE outbox_message DROP
COLUMN finished_at`. Rows that were deleted are not recoverable, and that is the intended behaviour.
