## Why

`outbox_message` and `outbox_status_history` grow forever. `PUBLISHED` rows are kept by design until something
deletes them, and nothing does. The README's runbook tells an operator to run a hand-written `DELETE` off-peak.
`starter-admin-endpoints` added a third terminal status, `DISCARDED`, and the `outbox-admin` spec already promises
that discarded rows are "purged by the same retention as `DEAD_LETTER`". No such retention exists. Found while
implementing `starter-admin-endpoints`.

## What Changes

- A retention purge job in `outbox-spring-boot-starter`, on `job-core`'s `SelfSchedulingLifecycle` under a leased
  `RunLock` named `ludwig-outbox-retention-purge`. This is the shape `audit` and `export` already use: every replica
  runs the schedule and exactly one does the work. Because it is a registered job, it appears under
  `GET /admin/v1/jobs` and can be run now through `jobs.operate`. No new administrative endpoint is added.
- Two retention periods, set under `ludwig.outbox.retention.*`:
  - `published` (default `P7D`) for `PUBLISHED`;
  - `terminal` (default `P30D`) for `DEAD_LETTER` and `DISCARDED`.

  Each pass is bounded (`batch-size`, default 500) and committed on its own. A message's history rows are deleted
  in the same transaction as the message.
- A new column, `outbox_message.finished_at`, set whenever a message reaches a terminal status and cleared when an
  administrator retries a `DEAD_LETTER` message. Retention is measured from it. Today `DEAD_LETTER` and `DISCARDED`
  rows carry no timestamp of their own.
- Each pass that deleted anything writes an `outbox.purged` audit event (category `outbox`) with the status, the
  cutoff and the count, after the batch commits. Metric `ludwig.outbox.purged{status}`.
- **BREAKING**: the starter now deletes rows by default. Set `ludwig.outbox.retention.enabled=false` to keep the
  old behaviour. Deleting a `PUBLISHED` row also frees its idempotency key, so a duplicate published after
  `retention.published` has elapsed is accepted and sent again. Startup fails if `retention.published` is shorter
  than `P1D`.

## Capabilities

### New Capabilities
- `outbox-retention`: how long the outbox keeps terminal messages and their history, and how deletion is recorded.

### Modified Capabilities
- `outbox-admin`: a retried `DEAD_LETTER` message leaves retention (its `finished_at` is cleared). The discard
  requirement's retention promise becomes testable.

## Non-goals

- Purging `FAILED`. `FAILED` is retry-pending, or terminal only when `dead-letter.enabled=false`, and a deployment
  that turned dead-lettering off has chosen to keep those rows where it can see them.
- Partitioning `outbox_message`.
- Archiving payloads somewhere before deletion.
- A dedicated `/admin/v1/outbox/purge` endpoint. `POST /admin/v1/jobs/ludwig-outbox-retention-purge/run` already
  covers it.

## Impact

- Module: `outbox-spring-boot-starter` (library; parent `common`). In-repo dependents the gate runs:
  `asyncapi-spring-boot-starter`, `audit-spring-boot-starter`, `crud-service-example` (service),
  `export-spring-boot-starter`, `file-action-spring-boot-starter`, `file-ingest-spring-boot-starter`,
  `messaging-db-spring-boot-starter`, `notification-service` (service), `user-settings-spring-boot-starter`.
- Shared contracts: `outbox-admin` is modified; `outbox-retention` is new. `run-lock` and `job-admin` are used
  as they are.
- Migration `0006-finished-at.sql` in the outbox changelog.
- No POM changes. The module already depends on `job-core` and `audit-core`.
- README (EN and RU): the configuration table and the "Data growth" section; the runbook's manual `DELETE` row is
  replaced. `CHANGELOG` (EN and RU).
