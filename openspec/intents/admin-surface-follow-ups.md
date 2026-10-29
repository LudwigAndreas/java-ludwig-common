# Administrative surface - follow-ups

Work deliberately left out of `admin-authorization` and `starter-admin-endpoints`, or found while implementing
them. Each item is a candidate for its own change. Evidence for the gate items is in
`openspec/changes/archive/*-admin-authorization/state.json` (`failures`, `pending_human`).

## Proposed changes (2026-10-28)

Every item below now has a proposed change under `openspec/changes/`, except the API-baseline run, which needs the
releases repository rather than code. Each change removes its own items from this file when applied.

| change | covers | apply after |
|---|---|---|
| `gate-hygiene` | image-pin false positive, PAT revoke race (a production defect), `StubFaultIT.slow`, `JdbcRunLockIT` | - |
| `admin-surface-polish` | §3 (`produces`, description keys); failsafe stays conditional | - |
| `file-action-cancelled-state` | cancelled submission recorded `APPLIED` | - |
| `outbox-retention-purge` | outbox retention | - |
| `reconciliation-run-history` | run history, asynchronous manual runs | - |
| `messaging-db-consumer-controls` | moving a log consumer's offset, which does not need a broadcast after all | - |
| `admin-delegation-claim` | delegation (`act`) claim | - |
| `admin-object-scope` | per-object grants (reconciliation first) | - |
| `admin-four-eyes-approval` | four-eyes approval | - |
| `grpc-admin-operations` | administrative operations over gRPC | - |
| `replica-control-broadcast` | broadcast channel, caches area | - |
| `replica-controls` | hot-reload, outbound tokens, circuit breakers | `replica-control-broadcast` |

Still open after these:
- Run `check_api_baseline.sh` where the releases repository is reachable, before the major release is cut.
- Adopt object scope in `jobs` and `fileingest`.
- Enumeration run history.
- Approval for replica controls.

## 1. Gate hygiene (found while verifying `admin-authorization`)

The image-pin false positive, the PAT revoke race and `StubFaultIT.slow` were fixed by `gate-hygiene`. What remains:

- **Release checklist: run the API baseline check with repository access.** `check_api_baseline.sh` could not resolve
  28 published baselines, so revapi compared every module against an empty archive. The `java.class.removed` entry for
  `ru.ludwigandreas.pat.web.PatCredentialNotPermittedException` (and the entries `starter-admin-endpoints` adds) have
  never been exercised. Run the gate where the releases repository is reachable before the major release is cut.
- **The migrations, protos and build-metadata self-tests are not run by the gate.** `gate.sh` runs
  `check_image_pins.sh --self-test` since `gate-hygiene`; the other three checkers' `--self-test` modes still run
  only by hand.
- **`JdbcRunLockIT` SSL-setup failure, not reproduced.** It failed once (2026-10, `PSQLException: An error occurred
  while setting up the SSL connection` in `migrate`). `gate-hygiene` ran it 30 times unchanged without a failure and
  set `sslMode=disable` on its datasource, which removes the SSL negotiation but is not a proven fix. Reopen if it
  recurs, with the container log.
- **Only if `security-spring-boot-starter` grows a test that needs a container: bind failsafe there.** The module
  binds no failsafe execution, so an `*IT` there would never run. Its context tests need no infrastructure and live in
  `unit/`, like `SecurityAutoConfigurationTest`, so nothing needs it today.

## 2. Capabilities explicitly out of scope

- **Delegation (`act`) claim** was added by `admin-delegation-claim`: the actor's `via`, the `via` trail filter and
  `ludwig.admin.human-callers.required-via`. Still open: recording the full delegation chain in the envelope (only
  the immediate acting party is stored), and reading delegation from mTLS or PAT callers.
- **Per-replica controls.** The broadcast was added by `replica-control-broadcast` - job-core's database channel
  with per-replica acknowledgements - and cache eviction is its first control (`cacheevict`). Still open:
  hot-reload, outbound token invalidation and circuit-breaker state (`replica-controls`), per-key eviction, and
  targeting a subset of replicas.
- **Moving a database-log consumer's offset** was added by `messaging-db-consumer-controls`: the pause is a row every
  replica's claim reads, so it needed no broadcast. Offsets of groups on a real Kafka cluster stay with Kafka's own
  tooling.
- **Per-object grants** were added by `admin-object-scope`: resource `admin.<area>`, dimension `object`, roles `ALL`
  or `NONE`, objects granted per subject; `reconciliation` is the first adopter. Still open: adopt object scope in
  `jobs` (job names) and `fileingest` (sources).
- **Four-eyes approval** was added by `admin-four-eyes-approval`: `ludwig.admin.approval.required`, the
  `/admin/v1/approvals` endpoints and job-core's store. Still open: approval chains (N of M), approver groups distinct
  from operation holders, notifying approvers, and approval of replica controls once those exist.
- **Administrative operations over gRPC** were added by `grpc-admin-operations`: `@AdminAccess` on
  `@LudwigGrpcService` methods, `admin-reason` metadata, one caller policy and evaluator for both transports. Still
  open: four-eyes approval over gRPC (such an operation fails startup today).
- **Reconciliation run history** was added by `reconciliation-run-history`. Still open from it: run history for
  enumeration walks, and for the `async-job` submit pass, which stay synchronous and unrecorded.

## 3. Small polish

Both items (`produces` on the discovery endpoint, missing description keys) were fixed by `admin-surface-polish`.

## 4. Found while implementing `starter-admin-endpoints`

Both items (the outbox's missing retention purge, the cancelled file-action submission ending `APPLIED`) were fixed
by `outbox-retention-purge` and `file-action-cancelled-state`.

- **Administrative listings filter with OData** since `admin-listing-odata-filter`: the six paged listings over stored
  rows take `$filter` only (their explicit filter parameters removed), keep keyset paging, and refuse the offset
  options. Still open: the listings that are not JPA rows (approvals, run locks, database-log consumer positions,
  jobs) keep explicit parameters; a cursor mode in the OData starter, should client-chosen ordering be wanted.
