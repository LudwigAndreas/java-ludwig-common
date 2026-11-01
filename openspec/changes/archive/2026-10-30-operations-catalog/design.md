## Context

See proposal.md - Why. The current state this design replaces:

- **Admin catalog** (`security.admin`):
  - `AdminOperation(id, area, tier, descriptionKey)` parsed from `<area>.<tier>`, with `AdminTier` READ, OPERATE and
    PRIVILEGED.
  - `AdminOperationContributor` beans, one per area (15 areas, 45 operations, 69 `@AdminAccess` HTTP endpoints, 5
    replica controls, gRPC methods).
  - `AdminGrants` maps `ludwig.admin.grants` (operation to roles).
  - `AdminSurfaceGuard` enforces on `/admin/**`. It refuses partners and personal access tokens, checks the
    acting party (`AdminCallerPolicy`), the grant, the reason (`Admin-Reason`), object scope (`AdminObjectScope`
    over the pseudo-resource `admin.<area>` with dimension `object`) and four-eyes approval.
- **Data scope** (`security.data`):
  - `ludwig.security.data.policies.<resource>.<action>.<ROLE>: <grant>` is compiled by `ScopePolicies` and served
    by `PolicyDataScopeProvider`.
  - `DataScopeProvider.scopeFor(principal, resourceType, action)` beans are unioned by `CompositeDataScopeProvider`.
  - `DataScopeMapping` binds dimensions (`owner`, `tenant`, `partner`, `object`, plus custom ones) to a resource's
    columns.
  - `DataAccessGuard` takes `(resourceType, action)`. Its callers are both services' repositories and services,
    and export's `SecurityReportScopeResolver`.
- **Business endpoints:** `@PreAuthorize("hasAnyRole(...)")`. These stay until `operations-services`.

Nothing here has been published, so the redesign carries no compatibility constraint.

## Goals / Non-Goals

**Goals:**
- One `Operation` type, one catalog, and one compiled grant table serve enforcement, row scope, discovery and audit.
  None of the four can disagree with another.
- Code names operations and dimensions only. The only code that compares role names is the mechanism that reads the
  table.
- A misconfiguration fails startup, naming the offending key.

**Non-Goals:**
- Moving the starter APIs that take roles, or the services' `@PreAuthorize` checks (both are follow-ups).
- Changing how roles are resolved (`AuthorityResolver`, the identity projection). This change only consumes them.

## Decisions

### 1. The operation: an action, with sensitivity and surface as properties

New package `ru.ludwigandreas.security.operation`:

```java
public record Operation(String id, Sensitivity sensitivity, Surface surface,
                        String resource, String action, String descriptionKey)
Operation.admin("outbox.message.discard", Sensitivity.PRIVILEGED, "<key>").on("outbox-message", DataAction.WRITE)
Operation.business("catalog.product.update", Sensitivity.CHANGE, "<key>").on("product", DataAction.WRITE)
```

- The id grammar is `^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*){2}$`: exactly three segments, `<area>.<object>.<verb>`.
- A read operation covers listing and fetching one kind of object. Each distinct change is its own operation.
- `Sensitivity`:
  - `READ` changes nothing.
  - `CHANGE` changes state.
  - `PRIVILEGED` can lose data, or changes who may do what, or whether a dependency is called at all.
- `Surface`:
  - `BUSINESS` is the service's own API.
  - `ADMIN` is the administrative surface under `/admin/**`.
- `resource` and `action` are optional. An operation without them touches no rows.
- `OperationContributor` beans declare operations, and `OperationCatalog` collects them.
- `AdminOperation`, `AdminTier`, `AdminOperationContributor` and `AdminOperationCatalog` are removed.

*Alternatives:*
- Keeping `<area>.<tier>` ids: rejected. The ids name buckets, so discovery and audit say "privileged" instead of
  "discard".
- Encoding sensitivity in the id: rejected. Then renaming a sensitivity renames every grant.
- Free-form ids: rejected, because uniform ids are what make the table and the audit readable across 15 modules.

### 2. One role-centric table, compiled once

```yaml
ludwig.security.roles:
  NOTIFICATION_SUPPORT:
    operations:
      outbox.message.read: ALL
      outbox.message.retry: ALL
      notification.delivery.read: TENANT
  NOTIFICATION_ADMIN:
    includes: [NOTIFICATION_SUPPORT]
    operations:
      outbox.message.discard: ALL
      notification.delivery.read: ALL
```

- **Role names.** They are normalised as `Authorities.normalizeRole` does today, so `X` and `ROLE_X` are the same
  role.
- **Scope values.** A scope is `ALL`, or dimensions joined by `+` (reusing `ScopeGrant`'s parser). `NONE` is
  refused: an absent entry already means none.
- **`includes`.** It is resolved transitively. A role's effective scope for an operation is the union over itself
  and everything it includes (`DataScope.union`). A cycle fails startup.
- **Compilation.** `OperationGrants` compiles the table into two indexes, role→(operation→scope) and
  operation→(role→scope). The first serves enforcement and discovery; the second serves the holders endpoint.
- **What it replaces.** `ludwig.admin.grants`, `ludwig.security.data.policies`, `ScopePolicies`, `AdminGrants` and
  `PolicyDataScopeProvider` are removed. `ludwig.security.data.default-access` is removed too: absence is always
  `NONE`.

*Alternatives:*
- Operation-centric table: rejected. An access review asks what a role may do, and here a role is defined in one
  block. The other index answers "who may do X" at runtime.
- Wildcards: rejected, because they grant future operations silently.
- Keeping two tables: rejected. "May call" and "which rows" would be allowed to disagree.

### 3. One authority: `OperationAuthority`

- `scope(principal, operationId)` returns a `DataScope`. It is the union of the table's scopes over the principal's
  roles, and of every contributed `DataScopeProvider`. The SPI becomes `scopeFor(principal, Operation)`, so a
  provider sees the operation's resource and action, and the identity projection's per-subject grants keep working.
- `holds(principal, operationId)` is `!scope(...).denies()`.
- An operation without a resource admits only `ALL` or `OBJECT`.

`AdminObjectScope` and the pseudo-resource `admin.<area>` disappear:
- an `ADMIN` operation can declare a resource, and the `object` dimension is granted directly
  (`PARTNER_OPS: { operations: { reconciliation.run.start: OBJECT } }`);
- `@AdminObject` path variables are checked against `scope(op).admits(OBJECT, value)`.

### 4. `@RequiresOperation`: one annotation, three enforcement points

`@RequiresOperation("<id>")` replaces `@AdminAccess`. `@AdminAuthenticated` is renamed `@RequiresAuthentication`
and is valid on any surface.

| Where | Enforced by |
|---|---|
| MVC handler of an `ADMIN` operation (under `/admin/**`) | `AdminSurfaceGuard`: partner refusal, acting party, PAT rule, holds, reason, object scope, approval, invocation binding |
| MVC handler or bean method of a `BUSINESS` operation | Spring method security: an `AuthorizationManager` registered for the annotation through `AuthorizationManagerBeforeMethodInterceptor`, so it works through proxies |
| gRPC method | `AdminAccessInterceptor`, renamed `OperationInterceptor`: the same evaluation |

- **One evaluation.** All three call `OperationAuthority` and the same `AdminAccessEvaluator`, renamed
  `OperationEvaluator`.
- **Denials.** They are published through the `AuthorizationEventPublisher`, so the existing audit listener records
  them. There is no second audit path.
- **Sensitivity rules apply on every surface.** `PRIVILEGED` requires the reason header, which is renamed from
  `Admin-Reason` to `Operation-Reason`, at most 500 characters (gRPC metadata `operation-reason`). It also refuses
  personal access tokens unless `ludwig.security.operations.allow-personal-access-tokens` is set.
- **Surface rules.**
  - `ADMIN` adds partner refusal, the acting-party requirement and approval eligibility.
  - An `ADMIN` operation on a handler outside `/admin/**` fails startup.
  - So does a `BUSINESS` operation on a handler under `/admin/**`.

### 5. Discovery

- `GET /me/operations` (`@RequiresAuthentication`). Each held operation is listed with
  `{id, sensitivity, surface, scope, description}`:
  - `scope` is `ALL` or the dimension list;
  - `description` is localized through `ProblemMessages`, as today.

  Only held operations are listed: listing the rest would map the permission model. It replaces
  `/admin/v1/me/operations`, and an admin UI filters on `surface=ADMIN`.
- `GET /admin/v1/security/operations/{id}/holders` is guarded by `security.operation.read` (an `ADMIN` `READ`
  operation that the security starter declares). It lists `{role, scope, via}`, where `via` is the including role
  when the grant was inherited.

### 6. Startup validation

`OperationCatalog` and `OperationGrants` fail startup on:
- a malformed id, a duplicate id, or a description key that resolves in no bundle;
- an operation of sensitivity `READ` whose data action is not `READ`, or one of sensitivity `CHANGE` or
  `PRIVILEGED` whose data action is `READ`;
- a table entry naming an unknown operation;
- `NONE`;
- an `includes` cycle, or an `includes` of an undefined role;
- a dimension that the operation's resource has no `DataScopeMapping` binding for;
- a dimension other than `OBJECT` on an operation that has no resource (`OBJECT` is bound by the named-object
  parameter, not by rows);
- `@RequiresOperation` naming an unknown id;
- a surface/path mismatch.

A declared operation that no role holds is logged at `WARN` and listed once. Roles come from the identity provider,
so an unknown role name cannot be validated.

### 7. Data access by operation

`DataAccessGuard` changes:
- `scope(op)`;
- `predicate(op[, mapping])`;
- `permits(op, target)`;
- `check(op, target[, id])`.

The resource comes from the operation, and calling it with an operation that has no resource throws
`IllegalStateException`. `DataScopeMapping` and `DataScopeRegistry` are unchanged.

Export's report definitions name the read operation instead of a resource type
(`ReportDefinition.scopedBy("catalog.product.read")`). This is the minimal change the guard's new signature forces;
export's role-based `visibleFor` stays until `operations-starter-apis`.

### 8. The operation table

62 `ADMIN` operations replace the 45 tier operations. Description keys stay in each module's existing bundle.

| Area | Operations (sensitivity) |
|---|---|
| audit | `audit.event.read` (R) |
| export | `export.run.read` (R), `export.run.cancel` (C), `export.subscription.read` (R) |
| fileaction | `fileaction.submission.read` (R), `fileaction.submission.cancel` (C) |
| fileingest | `fileingest.task.read` (R; tasks, task, run), `fileingest.run.start` (C) |
| hotreload | `hotreload.source.read` (R), `hotreload.source.reload` (C, control) |
| identity | `identity.partner.read` (R), `identity.partner.onboard` (P), `identity.partner.replace` (P), `identity.partner.suspend` (P), `identity.partner.activate` (P), `identity.grant.read` (R), `identity.grant.create` (P), `identity.grant.revoke` (P), `identity.authority.read` (R) |
| job | `job.definition.read` (R), `job.definition.run` (C), `job.lease.read` (R), `job.lease.release` (P) |
| replica | `replica.instance.read` (R), `replica.command.read` (R) |
| cache | `cache.definition.read` (R), `cache.performance.evict` (C, control), `cache.security.evict` (P, control) |
| messaging | `messaging.consumer.read` (R), `messaging.consumer.pause` (C), `messaging.consumer.resume` (C), `messaging.offset.move` (P) |
| outbox | `outbox.message.read` (R; list, get, summary), `outbox.message.retry` (C; one and bulk), `outbox.message.discard` (P) |
| pat | `pat.token.read` (R), `pat.token.issue` (P), `pat.token.revoke` (P) |
| reconciliation | `reconciliation.task.read` (R; overview, task, scope, runs, run), `reconciliation.run.start` (C), `reconciliation.backfill.start` (C), `reconciliation.cursor.reset` (C), `reconciliation.scope.reset` (C), `reconciliation.quarantine.requeue` (C), `reconciliation.member.requeue` (C), `reconciliation.remotejob.requeue` (C), `reconciliation.remotejob.cancel` (P), `reconciliation.lease.release` (P), `reconciliation.discovery.start` (C), `reconciliation.walk.start` (C), `reconciliation.walk.preview` (C), `reconciliation.sweep.discard` (C), `reconciliation.sweep.confirm` (P) |
| restclient | `restclient.client.read` (R), `restclient.breaker.read` (R, control), `restclient.token.reset` (C, control), `restclient.breaker.set` (P, control) |
| settings | `settings.subject.read` (R), `settings.consent.read` (R), `settings.scope.set` (C), `settings.backfill.start` (C) |
| security | `security.operation.read` (R; holders) |

Two rules decide the sensitivities:
- each operation keeps its current tier (`OPERATE` becomes `CHANGE`);
- consents are split out of the settings read, because consent history is personal data.

`BUSINESS` operations declared now, one per data-policy row, named for the verb the code performs:
- crud-service-example: `catalog.product.read`, `catalog.product.update` and `catalog.product.delete` (resource
  `product`).
- notification-service:
  - `notification.delivery.read`, `notification.delivery.retry`, `notification.delivery.cancel` and
    `notification.content.read` (resource `notification-delivery`);
  - `notification.request.read` (resource `notification-request`).

### 9. Replica controls and approvals

- `ReplicaControl.operationFor(args)` returns an `Operation`, and `possibleOperations()` returns a `Set<Operation>`.
  The startup check that they are catalogued stays.
- `ludwig.admin.approval.required` lists the new ids. Only `ADMIN` operations of sensitivity `PRIVILEGED` are
  eligible, as only `privileged` ones are today; others fail startup.

### Dependency-direction check

Does any touched module gain an in-repo dependency? No. Every module that declares operations already depends on
`security-spring-boot-starter` (its `inRepoDependents` lists all of them). grpc-server depends on it already;
architecture-rules only changes a message string. No edge is added, so no cycle is possible.

### POMs

No POM changes: not the root `pom.xml`, not `ludwig-bom`, not `ludwig-service-parent`. The root `pom.xml`'s revapi
configuration is unaffected, because `security.admin` was never released (v2.0.0 has none of these types).

### Mechanical checks for the new conventions

- **Id grammar, unknown ids, scope validity, `includes` cycles, surface/path consistency:** fail-closed startup
  checks in `OperationCatalog`, `OperationGrants` and the handler validator. These are runtime facts, such as
  configuration and catalog contents, that no static tool can see.
- **"No role in code":** an ArchUnit group in `architecture-rules`, added by `no-roles-in-code`. Adding it now would
  fail the build on the business checks that `operations-services` has not moved yet.
- **`@AdminAccess` gone:** the compiler enforces it. The annotation no longer exists.

### Reuse, not a second implementation

- Row scope is still `DataScope`, `ScopeGrant` and `DataScopeMapping`.
- Denials still go through `AuthorizationEventPublisher` to the existing audit listener.
- Refusals render through the ProblemDetail pipeline.
- Descriptions resolve through `ProblemMessages`.
- Administrative audit stays `AdminAudit` with its action being the precise operation id.

## Risks / Trade-offs

- [A large, cross-module cut: 18 modules and two services.] → Tasks go module by module, each with its own named
  verification. One reactor gate covers all dependents at the end. Nothing is published, so no consumer is broken
  in between.
- [Default deny plus a long table can lock operators out after a typo.] → Startup fails on every unknown name. The
  WARN lists operations held by nobody. The holders endpoint shows who holds what.
- [The table grows with precise operations.] → `includes` keeps it short. Each role is one block, which is what an
  access review needs.
- [Method security on business methods adds a proxy hop.] → The services already use `@PreAuthorize` through the
  same interceptor chain, so this adds no cost.
- [Pending approvals and historical audit events carry tier ids.] → Audit history is immutable and keeps them.
  Pending approvals naming an unknown operation are refused on decision, as an expired approval is. There are none
  in production.
- [`Operation-Reason` renames a header clients send.] → Only unpublished administrative clients send it, and the
  rename makes it valid for business `PRIVILEGED` operations too.

## Migration Plan

There is no data migration and no changeset. Deploying is code plus `application.yml` in the same release. Rollback
is the previous release with its own configuration. Both services' `application.yml` are rewritten in this change:
`ludwig.admin.grants` and `ludwig.security.data.policies` become `ludwig.security.roles` with the same effective
access, and each service's integration tests prove it is unchanged.
