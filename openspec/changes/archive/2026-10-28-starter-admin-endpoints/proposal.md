## Why

Operators fix stuck work and access problems today by hand-written SQL or not at all. The two modules with
controls (`reconciliation`, `file-ingest`) expose them as actuator endpoints gated only by "authenticated", hidden
behind a default exposure list that leaves them unreachable; outbox, run locks, the identity projection's partner
and grant tables, and the audit trail have no surface; and the starters that do have administrative REST endpoints
(`user-settings`, `pat`) each authorize them their own way. With `admin-authorization` in place, every module's
administrative controls can move onto one surface, under one authorization model, with one audit trail - and the
identity projection's documented known gap ("changes to `security_partner` and `security_grant` produce no
`AuditEvent`") closes as a side effect.

Ships in the next major release; breaking changes are permitted and are listed below.

## What Changes

Every endpoint is under `/admin/v1/<area>/`, authorized by an `<area>.<tier>` operation from `admin-authorization`.

- **Access (tier 1)**
  - `identity` (`identity-projection-spring-boot-starter`): partner registry (onboard, update, suspend, activate),
    data-scope grants (grant, revoke), a subject's effective authorities. Every write audited in its transaction.
  - `audit` (`audit-spring-boot-starter`): search the stored audit trail; each search is itself audited. Adds an
    actor-tenant column so a tenant administrator sees only their tenant's trail.
- **Stuck work (tier 2)**
  - `reconciliation`: every former actuator action as a REST resource.
  - `fileingest`: tasks, runs, manual trigger.
  - `outbox`: browse by status, retry, bulk retry (capped), discard (new terminal status `DISCARDED`).
  - `jobs` (`job-core`): registered jobs and their leases, run now, force-release a lease.
  - `messaging` (`messaging-db-spring-boot-starter`): consumer lag per partition (read only).
- **Cross-requester views (tier 3)**
  - `exports`: every requester's runs and subscriptions, cancel any run.
  - `fileactions`: every submitter's submissions, cancel any submission.
- **Alignment**
  - `settings` (`user-settings-spring-boot-starter`): the existing admin controllers move to `/admin/v1/settings/`.
  - `pat` (`pat-spring-boot-starter`): managing another owner's tokens moves to `/admin/v1/pat/`.
- **Services**: `notification-service` grants all operations to `NOTIFICATION_ADMIN` and the read tier to
  `NOTIFICATION_SUPPORT`; `crud-service-example` grants them to `CATALOG_ADMIN`. Published OpenAPI documents
  regenerated.

**BREAKING**
- Removed: the `reconciliation` and `fileingest` actuator endpoints, their auto-configurations and their
  properties (`ludwig.reconciliation.endpoint.*`, `ludwig.ingest.endpoint.*`).
- Removed: `ludwig.user-settings.access.admin-authority`, `ludwig.user-settings.web.admin-base-path`; the admin
  settings paths move from `/admin/settings/**` to `/admin/v1/settings/**`.
- Removed: `PatAuthorities.MANAGE_ANY` (`pat:manage:any`); the self-service PAT endpoints no longer accept another
  owner; that is the `pat` area's job.
- `OutboxStatus` gains `DISCARDED`; a consumer switching over the enum must handle it.
- `audit-core`'s `Actor` record gains a `tenant` component (canonical constructor changes).
- Administrative settings endpoints answer another tenant's subject with `404` instead of `403`.
- Six modules gain an optional dependency on `security-spring-boot-starter` (none had a cycle risk; see design).

## Capabilities

### New Capabilities
- `identity-admin`: partner registry, data-scope grants and effective-authority inspection.
- `audit-trail-search`: searching the stored audit trail, tenant-confined, with searches audited.
- `reconciliation-admin`: task inspection and every operator action on tasks, remote jobs, leases and enumeration scopes.
- `file-ingest-admin`: task and run inspection and the manual trigger.
- `outbox-admin`: browsing, retrying and discarding outbox messages.
- `job-admin`: registered jobs, their run leases, run-now and forced lease release.
- `messaging-db-admin`: consumer lag inspection.
- `export-admin`: cross-requester inspection and cancellation of export runs and subscriptions.
- `file-action-admin`: cross-submitter inspection and cancellation of file-action submissions.
- `settings-admin`: administrative settings and consent access on the shared surface.

### Modified Capabilities
- `personal-access-token`: issuing, listing and revoking another owner's PATs becomes the `pat` area's
  administrative operations instead of a distinct authority on the self-service API.
- `run-lock`: adds forced release by an administrator.

## Non-goals

- Moving a consumer's offset, pausing a consumer, or any control over per-replica in-memory state (caches,
  hot-reload sources, outbound tokens, circuit breakers). An administrative call lands on one replica; these
  controls would act on one replica and report success. Caches already have the security TTL and after-commit
  eviction.
- Showing any payload or content: outbox payloads, export outputs, file-action rejected rows, audit attributes
  beyond what the envelope already redacts.
- Idempotency-claim administration - lease expiry already frees a stuck claim.
- Changing notification-service's own administrative controllers, or `crud-service-example`'s `CATALOG_ADMIN`
  method checks.
- A deprecation period for the removed actuator endpoints - removed in this major release by the owner's decision.

## Shared contracts touched

- Modified: `personal-access-token`, `run-lock`.
- Relied on, not modified: `admin-authorization` (from the preceding change), `long-running-operations` (run and
  cancel endpoints answer `202` with the one envelope), `audit-envelope`, `problem-detail-pipeline`,
  `published-api-document`, `database-migration` (new changesets), `major-release-removals` (one `revapi.differences`
  entry per removed element), `starter-conditional-wiring`.

## Impact

| module | POM tier | change | in-repo dependents (gate) |
|---|---|---|---|
| `audit-core` | library | `Actor` gains a nullable `tenant` | `audit-spring-boot-starter`, `export-spring-boot-starter`, `file-action-spring-boot-starter`, `file-ingest-spring-boot-starter`, `grpc-client-spring-boot-starter`, `hot-reload-spring-boot-starter`, `idempotency-spring-boot-starter`, `messaging-spring-boot-starter`, `observability-spring-boot-starter`, `odata-filter-spring-boot-starter`, `outbox-spring-boot-starter`, `pat-spring-boot-starter`, `reconciliation-spring-boot-starter`, `rest-client-spring-boot-starter`, `security-spring-boot-starter` |
| `security-spring-boot-starter` | library | `PrincipalActorResolver` fills the actor's tenant | `crud-service-example`, `export-spring-boot-starter`, `file-action-spring-boot-starter`, `grpc-client-spring-boot-starter`, `grpc-server-spring-boot-starter`, `identity-projection-spring-boot-starter`, `notification-service`, `pat-spring-boot-starter`, `test-support-security`, `user-settings-spring-boot-starter` |
| `identity-projection-spring-boot-starter` | library | `identity` area | `crud-service-example`, `notification-service` |
| `audit-spring-boot-starter` | library | `audit` area; `actor_tenant` column; optional security dependency | `user-settings-spring-boot-starter` |
| `reconciliation-spring-boot-starter` | library | `reconciliation` area; actuator removed; optional security dependency | none |
| `file-ingest-spring-boot-starter` | library | `fileingest` area; actuator removed; optional security dependency | none |
| `outbox-spring-boot-starter` | library | `outbox` area; `DISCARDED`; optional security dependency | `asyncapi-spring-boot-starter`, `audit-spring-boot-starter`, `crud-service-example`, `export-spring-boot-starter`, `file-action-spring-boot-starter`, `file-ingest-spring-boot-starter`, `messaging-db-spring-boot-starter`, `notification-service`, `user-settings-spring-boot-starter` |
| `job-core` | library | `jobs` area; optional security and web dependencies | `audit-spring-boot-starter`, `export-spring-boot-starter`, `file-action-spring-boot-starter`, `file-ingest-spring-boot-starter`, `idempotency-spring-boot-starter`, `messaging-db-spring-boot-starter`, `notification-service`, `outbox-spring-boot-starter`, `reconciliation-spring-boot-starter` |
| `messaging-db-spring-boot-starter` | library | `messaging` area; optional security dependency | none |
| `export-spring-boot-starter` | library | `exports` area | `crud-service-example` |
| `file-action-spring-boot-starter` | library | `fileactions` area | `crud-service-example` |
| `user-settings-spring-boot-starter` | library | `settings` area replaces admin controllers | none |
| `pat-spring-boot-starter` | library | `pat` area; `MANAGE_ANY` removed | none |
| `notification-service` | service (parent `ludwig-service-parent`) | grants, OpenAPI document | none |
| `crud-service-example` | service (parent `ludwig-service-parent`) | grants, OpenAPI document | none |

Root `pom.xml`: `revapi.differences` entries only. `docs/admin-guide.md` (+ `.ru.md`) and every touched module's
README (+ `.ru.md`) gain their operation table. Depends on `admin-authorization` being applied first.
