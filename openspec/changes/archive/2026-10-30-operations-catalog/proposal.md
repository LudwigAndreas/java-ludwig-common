## Why

Authorization in a platform service is written two ways today, and neither lets a client ask "what may I do?":

- Administrative calls go through a catalog of `<area>.<tier>` operations granted to roles in
  `ludwig.admin.grants`.
- Business calls use role names hardcoded in code (`hasAnyRole('CATALOG_EDITOR', ...)`), plus a separate
  `ludwig.security.data.policies` table mapping role to row scope per resource and action.

So roles leak into compiled code, a grant change needs a release, the tier ids name buckets rather than actions,
and only the administrative half is discoverable. This change makes **the operation the only thing code
authorizes by**, and **a role only a configuration item**, through one catalog and one configuration table. It is the
foundation that the follow-up changes and every service build on. Nothing here has been published, so it is a clean
cut with no compatibility bridge.

## What Changes

- **BREAKING - one operation catalog for every surface.**
  - Every module declares its operations: an id `<area>.<object>.<verb>` (for example `catalog.product.update`,
    `outbox.message.discard`), a sensitivity (`READ`, `CHANGE`, `PRIVILEGED`), a surface (`BUSINESS`, `ADMIN`), an
    optional resource and data action, and a description key.
  - Each read operation covers listing and fetching one kind of object; each distinct change is its own operation.
  - The tier ids (`outbox.read`, `outbox.operate`, `outbox.privileged`) are removed. The 15 administrative areas are
    re-declared as 62 precise operations.
- **BREAKING - one configuration table, role-centric.**
  - `ludwig.security.roles.<ROLE>.operations.<operation>: <scope>` replaces both `ludwig.admin.grants` and
    `ludwig.security.data.policies`. `<scope>` is `ALL`, or one or more dimensions such as `OWN+TENANT`.
  - `includes: [<ROLE>...]` composes roles. Wildcards are not supported.
  - Holding an operation means holding a scope other than `NONE`.
- **BREAKING - code authorizes by operation.**
  - `@RequiresOperation("<id>")` replaces `@AdminAccess`, on MVC handlers, on service methods (through method
    security) and on gRPC methods.
  - `DataAccessGuard` and `DataScopeProvider` take an operation instead of a resource-and-action pair, and the
    operation supplies the resource.
  - Replica controls and approvals name the new operation ids.
- **Sensitivity and surface replace the tier.**
  - `PRIVILEGED` requires a stated reason and refuses personal access tokens, on every surface.
  - **BREAKING:** the reason header `Admin-Reason` becomes `Operation-Reason` (gRPC metadata `operation-reason`),
    and `ludwig.admin.allow-personal-access-tokens` becomes `ludwig.security.operations.allow-personal-access-tokens`.
  - `ADMIN` keeps the administrative rules: only under `/admin/**`, partners refused, approval eligible.
- **Discovery for everyone.**
  - `GET /me/operations` lists the caller's held operations with sensitivity, surface, scope and localized
    description. It replaces `/admin/v1/me/operations` (**BREAKING**).
  - `GET /admin/v1/security/operations/{id}/holders` lists the roles holding an operation, and with what scope.
- **Startup validation.** Startup fails on:
  - a malformed or duplicate id, or a missing description;
  - a grant naming an unknown operation;
  - an `includes` cycle;
  - a dimension that does not apply to the operation's resource;
  - a dimension on an operation that touches no rows.

  An operation no role holds is logged.
- **Both services move to the new model.** notification-service and crud-service-example replace their grants and
  data policies with `ludwig.security.roles`, and declare the business operations their data policies covered.
  Their `@PreAuthorize` role checks remain until `operations-services`.

## Capabilities

### New Capabilities
- `operation-authorization`: the operation catalog, the role-centric configuration table, row scope by
  operation (which no capability specified before), `@RequiresOperation`, discovery (`/me/operations`, holders),
  and startup validation.

### Modified Capabilities
- `admin-authorization`: tiers, `ludwig.admin.grants`, `@AdminAccess` and `/admin/v1/me/operations` are replaced
  by operation-authorization. What stays is what is specific to the administrative surface: path, partner refusal,
  PAT rule, reason header, audit, and acting party.
- `admin-approval`: `ludwig.admin.approval.required` names the new operation ids, and only `ADMIN` operations are
  eligible.
- `replica-control`: a control's operations are catalog operations.
- `grpc-server`: `@RequiresOperation` on gRPC methods.
- `personal-access-token`: the PAT refusal reads sensitivity, not tier.
- Every administrative area, for its operation ids, tiers and grant examples: `audit-trail-search`, `export-admin`,
  `file-action-admin`, `file-ingest-admin`, `config-reload-admin`, `identity-admin`, `job-admin`, `cache-admin`,
  `messaging-db-admin`, `outbox-admin`, `reconciliation-admin`, `outbound-client-admin`, `settings-admin`.

## Non-goals

These are the follow-up changes, in this order:

- **`operations-starter-apis`:** the starter APIs that still take roles: `@Filterable(roles = ...)` in
  odata-filter, `visibleFor(roles)` in export, and PAT's `MANAGE_OWN` authority. Code-defined
  `DataScopeProvider`s (the catalog's WATCHER provider) become contributed dimensions.
- **`operations-services`:** replacing the 28 `@PreAuthorize` role checks in the two services with
  `@RequiresOperation`.
- **`no-roles-in-code`:** the `architecture-rules` group that fails the build on a role named in code. It comes
  last, because it fails until the other changes are done.

Also out of scope:

- Wildcard grants, and role inheritance other than explicit `includes`.
- Mapping identity-provider permission claims onto operations.
- Per-object "may I act on this one" probes. Object scope still answers by refusing.
- A UI, and runtime editing of roles. The table is configuration, changed by deployment.

## Impact

Touched modules. Every one is a library parented by `common` unless marked otherwise; in-repo dependents from
`project-index.json` follow each.

- `security-spring-boot-starter` (starter): the catalog, the table, the mechanism, the guard, discovery and
  validation. Its dependents are audit, crud-service-example, export, file-action, file-ingest,
  grpc-client, grpc-server, hot-reload, identity-projection, job-core, messaging-db, notification-service, outbox,
  pat, reconciliation, rest-client, test-support-security and user-settings.
- Re-declared operations and `@RequiresOperation`, one area each (dependents in brackets):
  - `audit-spring-boot-starter` (user-settings)
  - `export-spring-boot-starter` (crud-service-example)
  - `file-action-spring-boot-starter` (crud-service-example)
  - `file-ingest-spring-boot-starter` (none)
  - `hot-reload-spring-boot-starter` (export, notification-service, observability, user-settings)
  - `identity-projection-spring-boot-starter` (crud-service-example, notification-service)
  - `job-core` (audit, export, file-action, file-ingest, hot-reload, idempotency, messaging-db,
    notification-service, outbox, reconciliation, rest-client), which also carries the jobs, replicas and caches
    areas and the approval store
  - `messaging-db-spring-boot-starter` (none)
  - `outbox-spring-boot-starter` (asyncapi, audit, crud-service-example, export, file-action, file-ingest,
    messaging-db, notification-service, user-settings)
  - `pat-spring-boot-starter` (none)
  - `reconciliation-spring-boot-starter` (none)
  - `rest-client-spring-boot-starter` (crud-service-example, export, reconciliation, test-support-local)
  - `user-settings-spring-boot-starter` (notification-service)
- `grpc-server-spring-boot-starter` (crud-service-example, grpc-client): its interceptor reads
  `@RequiresOperation`.
- `grpc-core` (grpc-client-spring-boot-starter, grpc-server-spring-boot-starter, ludwig-grpc-api-parent): the metadata key `admin-reason` is renamed `operation-reason`.
- `architecture-rules` (rules jar; many dependents): `RuleGroup.GRPC`'s message names `@RequiresOperation`.
- `notification-service` and `crud-service-example`, both services parented by `ludwig-service-parent` with no
  dependents: `application.yml`, business operation declarations, data-scope calls, and the OpenAPI documents.
- Shared contracts: the specs listed under Capabilities.
- No POM changes and no new in-repo dependency edges: every module above already depends on the security starter.
- READMEs (EN and RU) of every touched module, the admin guide (EN and RU), both OpenAPI documents, and
  `CHANGELOG` (EN and RU).
