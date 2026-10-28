## Context

The change lives entirely inside `notification-service` and adds behaviour to the shared contract
`platform-announcement`. See proposal.md - Why.

Current state that shapes the approach:

- A single predicate, `visibleTo(roles, now)` in `AnnouncementQueryRepositoryImpl`, filters every
  recipient read: the list, the unread count and `findVisible` by identifier.
- Retention `purgeExpiredBefore(cutoff)` deletes on `visible_until < cutoff`.
- The table has `CHECK (visible_until > visible_from)`.
- `AnnouncementEntity` extends `AuditedEntity`, which carries `@Version`.
- `AnnouncementEmailBroadcastService.requestCancellation` already cancels a run idempotently. It
  documents an ordering hazard: no query may follow a persisted audit event in the same transaction.

## Goals / Non-Goals

**Goals:** withdrawal and guarded window edits as specified, with no second implementation of
audit, problem mapping or fan-out cancellation.

**Non-Goals:** the proposal's non-goals, and no change to the email fan-out's scheduling when the
window moves.

## Decisions

### Withdrawal is a `withdrawn_at` marker, not `visible_until = now`

Setting the end to now would be one line and would need no predicate or retention change. It fails
for an announcement whose window has not opened: `now < visible_from` violates the window check. It
would also overwrite the original window, which the audit trail should keep.

Instead:

- New nullable columns: `withdrawn_at`, `withdrawn_by` and `withdrawal_reason` (max 500 chars).
- `visibleTo` gains `withdrawnAt.isNull()`. Because it is the single shared predicate, list, count
  and by-identifier reads all change at once. A by-identifier read of a withdrawn announcement is
  then a `404` through the existing `AnnouncementNotFoundException`.
- `purgeExpiredBefore` becomes `visible_until < cutoff OR withdrawn_at < cutoff`.
- A partial index on `withdrawn_at WHERE withdrawn_at IS NOT NULL` keeps the extra `OR` branch from
  scanning the table.

### Endpoints follow the existing named-command shape

- `POST /api/v1/announcements/{id}/withdrawal` with body `{reason?}`. It mirrors
  `/email-run/cancellation`: a command that is idempotent on repeat.
- `PUT /api/v1/announcements/{id}/window` with body `{visibleFrom?, visibleUntil}`. The window is
  a small sub-resource replaced whole. An omitted `visibleFrom` means "keep the current start".
- Both use `@PreAuthorize("hasRole('NOTIFICATION_ANNOUNCER')")` and return
  `AnnouncementAdminResponse`. The response and `AnnouncementAdminView` gain `withdrawnAt`,
  `withdrawnBy` and `withdrawalReason`.

### Validation in `AnnouncementAdminService`, reusing `requireWindowWithinLimit`

Window-change checks, in order:

1. Withdrawn → `AnnouncementWithdrawnException` (`ProblemStatus.CONFLICT`).
2. Window already opened and the start changed → `AnnouncementWindowOpenedException` (`CONFLICT`).
3. `visibleUntil <= now`, or the new start in the past → `InvalidVisibilityWindowException`
   (`UNPROCESSABLE`).
4. Length and ordering → the existing `requireWindowWithinLimit`.

`correct()` gains check 1. The new exceptions extend `LocalizedException` like their siblings, and
their keys go into both `notification-messages.properties` and `notification-messages_ru.properties`.

### Withdrawal reuses `requestCancellation`, ordered around the audit hazard

The steps inside one `@Transactional` method:

1. Read the announcement. If it is already withdrawn, return it.
2. Read the run with `broadcastService.runFor`.
3. Set the withdrawal fields.
4. If a run exists, call `requestCancellation`, which does its own read, write and audit.
5. Record `announcement-withdrawn`. Nothing follows it.

`requestCancellation`'s reads come before any audit record, so they cannot trigger the auto-flush
the hazard describes. The withdrawal audit is written last, so it is followed by no query.

Alternative considered: setting the run's `cancelRequested` directly. Rejected, because it would be
a second cancellation path with its own audit.

### Audit

`AnnouncementAuditEvent` gains `WITHDRAWN = "announcement-withdrawn"` and
`WINDOW_CHANGED = "announcement-window-changed"` with factories in the style of `corrected(...)`. The
window factory carries the old and new `visibleFrom`/`visibleUntil` in its details map, the same
shape `published(...)` uses.

### Dependency-direction check

Does `notification-service` gain any in-repo dependency? No. Every type used (`LocalizedException`,
`ProblemStatus`, `AuditSink`) is already on its classpath. `web-core-spring-boot-starter` is a
direct dependency, and `audit-core` arrives transitively, as it already does for
`AnnouncementEmailBroadcastService`. `project-index.json` lists no in-repo dependents for
`notification-service`, so no cycle is possible.

### POMs

No POM changes: neither the root `pom.xml`, `ludwig-bom` nor `ludwig-service-parent`.

### Conventions

The change introduces no new convention, so no ArchUnit, Checkstyle or enforcer rule is needed.
Existing rules (layering, the i18n bundle key-set parity check) cover the new code.

## Risks / Trade-offs

- **[Risk]** Two announcers edit and withdraw at once. → `@Version` on `AuditedEntity` makes the
  loser fail with an optimistic-lock conflict instead of a silent overwrite. One IT asserts it.
- **[Risk]** A future reader query bypasses `visibleTo` and shows withdrawn announcements. → The
  visibility IT gains withdrawn cases for list, count and by-identifier reads.
- **[Trade-off]** Withdrawal cannot recall sent email. The spec says so explicitly, and the run's
  status shows how many deliveries were created.
- **[Trade-off]** Withdrawal is one-way. Restoring means publishing anew, which keeps the audit
  story linear.

## Migration Plan

- New Liquibase file `0024-announcement-withdrawal.sql` adds three nullable columns and a partial
  index. It is added with an explicit `<include>` in `db.changelog-master.xml` after `0023`.
  Existing rows stay valid with null values.
- Rollback: drop the index and columns. The application rolled back alongside ignores them.
