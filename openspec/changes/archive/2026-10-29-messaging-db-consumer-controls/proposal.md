## Why

When a consumer of a log-backed topic is stuck on a poison record, or has to replay a day after a downstream bug
fix, the only remedy today is hand-written SQL against `consumer_offset` while the service runs. That races the
consumers and leaves no audit record. `messaging-db-admin` says explicitly "No endpoint SHALL move an offset in this
release", and `openspec/intents/admin-surface-follow-ups.md` §2 deferred it as needing "a coordinated pause of the
consumer on every replica".

That premise turns out not to hold for the database log. A consumer claims a partition by holding the row lock on
its `consumer_offset` row (`FOR UPDATE ... SKIP LOCKED`) for the whole of one record's handling, and commits the new
position in the same transaction (`LogStore.processNext`). A pause stored in the database is therefore seen by every
replica's next claim, and an offset move taken under the same row lock cannot interleave with a delivery. Neither
needs a broadcast to replica memory.

## What Changes

New operations under the existing `messaging` area of `messaging-db-spring-boot-starter`:

- `POST /admin/v1/messaging/consumers/{group}/topics/{topic}/pause` and `.../resume` (`messaging.operate`). A pause
  is a row in a new `consumer_pause` table. Every replica's claim query excludes paused (group, topic) pairs, so the
  pause takes effect cluster-wide within one poll, and at most one in-flight record per partition finishes. Pause
  and resume are idempotent.
- `POST /admin/v1/messaging/consumers/{group}/topics/{topic}/offsets` (`messaging.privileged`, reason required) with
  `{partition?, to: earliest | latest | offset | timestamp, value?}` moves the group's committed position:
  - The group must be paused on that topic first, or the request is `409`. This is the deliberate two-step that
    Kafka's own reset tool enforces through an inactive group.
  - The response reports, per partition, the old and new positions and how many records are skipped (moved
    forward) or replayed (moved back).
  - Retry state (`attempts`, `next_attempt_at`) is reset.
- `GET /admin/v1/messaging/consumers` additionally shows whether each (group, topic) is paused, by whom, and since
  when.
- Every pause, resume and move is audited through `AdminAudit`, with the reason, positions and counts, and no
  payload.
- Migration `0005-create-consumer-pause.sql`, written under the same advisory lock and `IF NOT EXISTS` discipline as
  the existing log schema, because several services share it.

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `messaging-db-admin`: the read requirement drops "no endpoint SHALL move an offset" and shows pause state. New
  requirements cover pause, resume and moving an offset.

## Non-goals

- Moving offsets on real Kafka topics. That needs Kafka's group coordination; this change covers only the
  database log.
- Clearing consumer-side dedup state. A replayed record still held by the dedup store is skipped as a duplicate,
  exactly as a redelivery would be, and the README says so.
- Dead-letter replay.
- Using `replica-control-broadcast`. It is not needed here; see Why.

## Impact

- Module: `messaging-db-spring-boot-starter` (library; parent `common`). No in-repo dependents.
- Shared contract: `messaging-db-admin` is modified. `db-log-transport`'s delivery guarantees are unchanged: a move
  is an operator's explicit decision, recorded in the audit trail.
- No POM changes.
- READMEs (EN and RU), runbook entries ("a poison record", "replay after a fix"), both services' OpenAPI documents
  if they run the starter, and `CHANGELOG` (EN and RU).
