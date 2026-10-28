All tasks are in `notification-service`. `IT=` below abbreviates
`mvn -pl :notification-service -am verify -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=`.

## 1. Schema

- [x] 1.1 Add `0024-announcement-withdrawal.sql`: nullable `withdrawn_at`, `withdrawn_by` and
  `withdrawal_reason` (varchar 500) on `notification_announcement`, plus a partial index on
  `withdrawn_at WHERE withdrawn_at IS NOT NULL`. Include it in `db.changelog-master.xml` after
  `0023`. Extend `AnnouncementSchemaIT` to assert the columns and the index. Verify:
  `${IT}AnnouncementSchemaIT`
- [x] 1.2 Map the three columns on `AnnouncementEntity`. Add `withdrawnAt`, `withdrawnBy` and
  `withdrawalReason` to `AnnouncementAdminView`, `AnnouncementAdminResponse` and the mapper.
  Verify: `mvn -pl :notification-service -am compile`

## 2. Visibility and retention

- [x] 2.1 Add `withdrawnAt.isNull()` to `visibleTo` in `AnnouncementQueryRepositoryImpl`. Add
  withdrawn cases to `AnnouncementVisibilityIT`: list, count, by-identifier `404`, and a
  not-yet-open announcement withdrawn and then reaching its start. Verify:
  `${IT}AnnouncementVisibilityIT`
- [x] 2.2 Make `purgeExpiredBefore` also delete when `withdrawn_at < cutoff`. Add to
  `AnnouncementRetentionIT` an announcement withdrawn past the TTL whose original end is in the
  future. Verify: `${IT}AnnouncementRetentionIT`

## 3. Service

- [x] 3.1 Add `AnnouncementWithdrawnException` (`CONFLICT`), `AnnouncementWindowOpenedException`
  (`CONFLICT`) and `InvalidVisibilityWindowException` (`UNPROCESSABLE`). Put their message keys in
  both `notification-messages.properties` and `notification-messages_ru.properties`. Verify:
  `mvn -pl :notification-service -am verify` (the bundle key-set parity check)
- [x] 3.2 Add `WITHDRAWN` and `WINDOW_CHANGED` audit actions and factories to
  `AnnouncementAuditEvent`. The window factory carries the old and new from and until values.
  Extend `AnnouncementAuditTest`. Verify:
  `mvn -pl :notification-service -am test -Dtest=AnnouncementAuditTest -Dsurefire.failIfNoSpecifiedTests=false`
- [x] 3.3 Add `AnnouncementAdminService.withdraw(id, reason)`, ordered as design.md describes: reads
  first, then fields, then `requestCancellation` if a run exists, then the audit record last. A
  repeat withdrawal returns the announcement unchanged with no audit. Verify via 4.3.
- [x] 3.4 Add `AnnouncementAdminService.changeWindow(id, from, until)` with checks in the design's
  order, reusing `requireWindowWithinLimit`. Add the withdrawn guard to `correct()`. Verify via 4.3.

## 4. API

- [x] 4.1 Add `POST /api/v1/announcements/{id}/withdrawal` (body `WithdrawAnnouncementRequest
  {reason?}`, `@Size(max = 500)`) to `AnnouncementAdminController`, with
  `hasRole('NOTIFICATION_ANNOUNCER')`. Verify via 4.3.
- [x] 4.2 Add `PUT /api/v1/announcements/{id}/window` (body `ChangeAnnouncementWindowRequest
  {visibleFrom?, visibleUntil}`, `visibleUntil` `@NotNull`) with the same role. Update the
  `correct` endpoint's OpenAPI text, which currently says the window cannot be changed. Verify via
  4.3.
- [x] 4.3 Add `AnnouncementLifecycleIT` covering every scenario in the delta spec. Include
  withdrawal during a running fan-out (reuse `AnnouncementEmailFanOutIT`'s setup), the `403` with
  no role, every `409` and `422` case, and a concurrent edit failing on the version. Verify:
  `${IT}AnnouncementLifecycleIT`

## 5. Docs

- [x] 5.1 Document withdrawal and window changes in `services/notification-service/README.md`'s
  announcement section. Verify: `openspec validate add-announcement-withdrawal-and-window-edits --strict`
- [x] 5.2 Regenerate the published OpenAPI document for the two new endpoints. Verify:
  `scripts/api-docs.sh refresh`, then `scripts/api-docs.sh check`

## 6. Verification gate

- [x] 6.1 `mvn -q validate`
- [x] 6.2 `mvn -pl :notification-service -am verify`
- [x] 6.3 `openspec validate add-announcement-withdrawal-and-window-edits --strict`
