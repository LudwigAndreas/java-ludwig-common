## Why

The platform has no administrative surface of its own. Two starters expose operator controls as actuator
endpoints whose only gate is `anyRequest().authenticated()`, and the starters that do have administrative REST
endpoints each invented their own authorization: hard-coded roles, a configurable authority, a published
permission constant. A starter cannot name a role, because roles belong to the service that consumes it - one
service's administrator is `NOTIFICATION_ADMIN`, another's is `CATALOG_ADMIN` - and nothing today lets a service
say which of its roles may perform which administrative operation of a starter it uses. This change builds that
mechanism once, so that `starter-admin-endpoints` can put every module's controls behind it.

It ships in the next major release; breaking changes are permitted.

## What Changes

- A starter declares the **administrative operations** it offers as a catalog of ids of the form
  `<area>.<tier>`, in three tiers: `read`, `operate`, `privileged`. A starter never names a role.
- A service grants operations to **its own roles** in configuration (`ludwig.admin.grants`), e.g.
  `outbox.operate: [NOTIFICATION_ADMIN]`. Roles are resolved exactly as today - projection, `service-roles`,
  partner roles; no token claim is read for roles. The PAT intersection is untouched.
- **Fail closed** at every seam: an ungranted operation is refused; a grant naming an operation no starter
  declares fails startup; a handler naming an uncatalogued operation fails startup; without
  `security-spring-boot-starter` no administrative endpoint is registered.
- A dedicated **administrative surface** at `/admin/v1/**`, guarded before any handler whatever filter chain the
  service runs: humans and service principals only (partners refused), PAT-credentialed callers refused by default
  with the existing credential-not-permitted problem type.
- A `privileged` operation requires a stated **reason** (`Admin-Reason` header); every permitted `operate` and
  `privileged` invocation is recorded through the one `AuditSink` with actor, operation, target, reason and outcome.
- **Tenant confinement**: on resources that carry a tenant, a human caller is confined to their own tenant; a
  service principal is not, the same rule `user-settings` already applies.
- `GET /admin/v1/me/operations` tells a caller (typically an administrative UI) which operations it holds.
- A new ArchUnit rule group, `RuleGroup.ADMIN`, owns the structural rules of the surface.

## Capabilities

### New Capabilities
- `admin-authorization`: the operation catalog, the service-owned grant mapping, the administrative surface's
  guard, the reason and audit contract, tenant confinement, and caller self-discovery.

### Modified Capabilities
- `enforcement-triad`: adds the requirements that place the administrative surface's structural rules in one
  ArchUnit rule group and record its unmechanisable rules.

## Non-goals

- Reading roles or permissions from token claims, or parsing a delegation (`act`) claim. Identity comes from the
  token, entitlement from the projection, as the platform already decides.
- Any module's administrative endpoints. Those are `starter-admin-endpoints`; this change ships the mechanism
  with a test fixture and no production operations.
- Wildcard grants (`outbox.*`). A later release adding an operation would be granted silently.
- Four-eyes approval, administrative operations over gRPC, and broadcasting a control to every replica.
- Changing how a service authorizes its own, non-starter controllers (notification's `hasRole('NOTIFICATION_ADMIN')`
  stays as it is).

## Shared contracts touched

- `enforcement-triad` - modified (ADDED requirements).
- Read and relied on, not modified: `personal-access-token` (a PAT may not reach a management surface),
  `problem-detail-pipeline` (credential-not-permitted is a distinct `403`; mappers, never an advice),
  `audit-envelope` (one sink; records name the object, never its contents), `published-api-document`.

## Impact

| module | POM tier | change | in-repo dependents (gate) |
|---|---|---|---|
| `security-spring-boot-starter` | library (parent `common`) | new `ru.ludwigandreas.security.admin` package: catalog SPI, grant properties, evaluator, method-security annotation, surface guard, `/admin/v1/me/operations` | `crud-service-example`, `export-spring-boot-starter`, `file-action-spring-boot-starter`, `grpc-client-spring-boot-starter`, `grpc-server-spring-boot-starter`, `identity-projection-spring-boot-starter`, `notification-service`, `pat-spring-boot-starter`, `test-support-security`, `user-settings-spring-boot-starter` |
| `architecture-rules` | rules (parent `common`) | new `RuleGroup.ADMIN` | `asyncapi-spring-boot-starter`, `crud-service-example`, `file-action-spring-boot-starter`, `file-ingest-spring-boot-starter`, `grpc-client-spring-boot-starter`, `grpc-server-spring-boot-starter`, `messaging-db-spring-boot-starter`, `messaging-spring-boot-starter`, `notification-service`, `object-storage-spring-boot-starter`, `odata-filter-spring-boot-starter`, `pat-spring-boot-starter`, `user-settings-spring-boot-starter` |
| `test-support-security` | library (parent `common`) | none - its existing `TestPrincipalBuilder` already authenticates a test request as a `USER` or `SERVICE` holding given roles, which is all the surface guard reads | (test-scoped only) |

No new in-repo dependency and no third-party dependency. `docs/admin-guide.md` (+ `.ru.md`) gains the
administrative-surface section. Every service's published OpenAPI document (`docs/api/*.openapi.yaml`) gains
`GET /admin/v1/me/operations`, because the security starter serves it in every service that uses it.
