## Why

Once published, an announcement can only have its text re-rendered. An announcer who published the
wrong thing, to the wrong people, or for the wrong period has no way to take it down or move its
dates - the banner stays up until its window closes. Operating announcements in production needs both.

## What Changes

- **Withdraw** a published announcement (`POST /api/v1/announcements/{id}/withdrawal`, role
  `NOTIFICATION_ANNOUNCER`, optional reason). It disappears from every recipient's list, count and
  by-identifier read at once. The row is kept, marked with who withdrew it, when and why, and
  retention purges it as it does any announcement whose visibility has ended.
- Withdrawing also **cancels a running email fan-out**. Emails already sent are not recalled.
- **Change the visibility window** (`PUT /api/v1/announcements/{id}/window`), with guards:
  - the end may be moved either way, but not into the past;
  - the start may move only while the window has not opened yet;
  - the configured maximum window length still applies.
- A withdrawn announcement is final: its window and its content can no longer be changed.
- Withdrawals and window changes are audited, and a window change records its old and new values.
- The audience stays immutable, as before.

## Non-goals

- Hard deletion of an announcement by an announcer. Deletion stays with retention.
- Recalling or "unsending" emails already delivered.
- Un-withdrawing (republishing a withdrawn announcement). To restore it, the announcer publishes a new one.
- Changing the audience or category after publication.
- An admin listing or search of announcements.
- Restarting or re-targeting an email fan-out after a window change.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `platform-announcement`: adds requirements for withdrawal, guarded window changes and the
  finality of a withdrawn announcement. Existing requirements are unchanged. Withdrawal ends
  visibility early, which the existing window and retention requirements already account for.

## Impact

- **Module**: `notification-service`, service tier (parent `ludwig-service-parent`). In-repo
  dependents: none, so the gate is `mvn -q validate` and `mvn -pl :notification-service -am verify`.
- **Shared contract**: `openspec/specs/platform-announcement/spec.md` (ADDED requirements only). No
  other spec in `openspec/specs/` is touched.
- **API**: two new endpoints on the existing announcement admin resource. The admin response gains
  withdrawal fields. These changes are additive and nothing breaks.
- **Data**: one Liquibase change adds nullable `withdrawn_at`, `withdrawn_by` and
  `withdrawal_reason` columns to `notification_announcement`.
- **Audit**: two new `AnnouncementAuditEvent` actions, `announcement-withdrawn` and
  `announcement-window-changed`.
