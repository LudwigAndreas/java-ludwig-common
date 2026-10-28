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

- **Delegation (`act`) claim.** Record "alice via admin-console" and optionally require administrative calls from
  people to arrive through the administrative UI. Depends on the identity provider issuing the claim; the audit
  envelope already has `on_behalf_of`.
- **Per-replica controls.** Cache eviction, hot-reload, outbound token invalidation and circuit-breaker state act on
  the one replica a call lands on. A broadcast (control event over Kafka, "accepted" not "done") is needed before
  any of these get administrative endpoints.
- **Moving a database-log consumer's offset.** Needs a coordinated pause of the consumer on every replica.
- **Per-object grants** (operate one reconciliation task but not another): a data-scope binding, not a new grant
  syntax.
- **Four-eyes approval** for privileged operations.
- **Administrative operations over gRPC.**
- **Reconciliation run history.** A run is answered `202` with the task as its status resource because there is no
  run table.

## 3. Small polish

Both items (`produces` on the discovery endpoint, missing description keys) were fixed by `admin-surface-polish`.

## 4. Found while implementing `starter-admin-endpoints`

- **The outbox has no retention purge.** `PUBLISHED`, `DEAD_LETTER` and now `DISCARDED` rows accumulate forever in
  `outbox_message` and `outbox_status_history`. A purge job (on `job-core`, keyed on `published_at` / last
  transition) and its retention properties are a change of their own.
