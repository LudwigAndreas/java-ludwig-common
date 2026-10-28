## Context

See proposal.md - Why. The facts that shape the approach:

- `ResourceServerAutoConfiguration` builds one chain whose only rule beyond `public-paths` is
  `anyRequest().authenticated()`; per-endpoint authorization is method security, which
  `DataAuthorizationAutoConfiguration` already enables (`@EnableMethodSecurity`, with decisions published as events
  that `AuthorizationDeniedAuditListener` records).
- `Authorities` already separates `roles` from `permissions`, and `Authorities.normalizeRole` already accepts both
  spellings. Roles reach a principal from the identity projection (users), `security_partner_role` (partners) and
  `ludwig.identity.service-roles` (service principals). `JwtPrincipalConverter` deliberately reads no role claim.
- The invariant `effective = authoritiesOf(owner, now) ∩ token.scopes()` lives only in `AttenuatedAuthentications`
  and is fenced by `RuleGroup.CREDENTIALS`.
- Three authorization styles exist today for administrative endpoints: hard-coded roles (notification), a
  configurable authority (`SettingsAccessPolicy`), a published permission constant (`PatAuthorities.MANAGE_ANY`).

## Goals / Non-Goals

**Goals:** one evaluation path for every administrative endpoint on the platform; a mapping a reviewer can read
in one YAML block; every misconfiguration visible at startup rather than as a `403` in production.

**Non-Goals:** expressing grants per object (per task, per topic). A grant is per operation. If per-object grants
are needed later they are a data-scope binding, not a grant-syntax extension.

## Decisions

### 1. Operations are catalogued beans; the id is `<area>.<tier>`

A module contributes `AdminOperation` values (id, area, tier, i18n description key) through an
`AdminOperationContributor` bean; `AdminOperationCatalog` collects them and validates grammar and uniqueness at
startup. Each module publishes its ids as constants (`OutboxAdminOperations.OPERATE = "outbox.operate"`).

*Alternatives.* Fine-grained ids per endpoint (`outbox.message.retry`) - rejected: a service's grant block would
grow by one line per endpoint per release, and every new endpoint would be ungranted until someone noticed;
three tiers per area is what an operator can reason about. An enum per module - rejected: the catalog spans
modules that do not know each other.

### 2. Grants are `Map<operationId, List<role>>` under `ludwig.admin.grants`

```yaml
ludwig:
  admin:
    grants:
      outbox.read:       [NOTIFICATION_ADMIN, NOTIFICATION_SUPPORT]
      outbox.operate:    [NOTIFICATION_ADMIN]
      outbox.privileged: [NOTIFICATION_ADMIN]
```

Keyed by operation rather than by role because the question a reviewer asks is "who can discard outbox
messages?", and keyed this way it is one line. Spring's map binding of dotted keys needs the bracket form in
properties files (`ludwig.admin.grants[outbox.read]`); YAML as above binds as-is - the README says so, and the
startup check catches the case where the binder split the key (it would arrive as `outbox` → unknown id).

*Alternatives.* A role → operations map - rejected: same information, but the who-can question needs a scan.
Expanding roles into `Authorities.permissions` inside the resolver - rejected: it would put administrative
vocabulary into every principal of every request and into the authority cache, and into the PAT intersection,
where a PAT scope string could then name an administrative operation. Evaluating at the endpoint keeps the
mapping out of the credential path entirely, so `RuleGroup.CREDENTIALS` is untouched.

### 3. One annotation, evaluated by the surface guard, not by method-security AOP

`@AdminAccess(OutboxAdminOperations.OPERATE)` on a handler method. The guard of decision 4 reads it from the
`HandlerMethod` in `preHandle`, after the surface checks: grant check (`403` via `AccessDeniedException`, with an
`AuthorizationDeniedEvent` published through the existing `AuthorizationEventPublisher` so the existing audit
listener records it), then the reason check for `PRIVILEGED`, then binds the `AdminInvocation` for the request and
unbinds it in `afterCompletion`. A handler under `/admin/` carrying neither `@AdminAccess` nor `@AdminAuthenticated`
(the marker for "any caller the guard admits", used by the discovery endpoint) is refused `403` and logged - fail
closed at runtime as well as at build time.

*Alternative, chosen first and rejected during implementation.* An `AuthorizationManager` behind an
`AuthorizationManagerBeforeMethodInterceptor` advisor. Method-security advisors are applied only while
`@EnableMethodSecurity` is active, and in this module that is `DataAuthorizationAutoConfiguration`, switched off by
`ludwig.security.data.enabled=false`: the annotation would then be silently ignored, which is fail-open. The
interceptor runs on every request under `/admin/` regardless, and makes "authorization before reason" an ordering
in one method instead of an ordering between two advisors.

*Alternative.* `@PreAuthorize("@ludwigAdmin.allows(authentication, 'outbox.operate')")` - rejected: the id is an
opaque string inside SpEL, invisible to ArchUnit and to the startup check, and a typo is a permanent `403`.

### 4. A handler interceptor guards the surface, not a second filter chain

`AdminSurfaceGuard`, a `HandlerInterceptor` registered for `/admin/**`, runs in `preHandle` - before the handler and
before the `@AdminAccess` proxy around it. It refuses an unauthenticated caller
(`AuthenticationCredentialsNotFoundException`, rendered `401` by `SecurityProblemMapper`), a `PARTNER`
(`AccessDeniedException`, `403`), and a PAT credential unless allowed (`PatCredentialNotPermittedException`, which
moves from `pat-spring-boot-starter` into the security starter so both surfaces throw the one type and render the
one problem `type` through web-core's pipeline). Registered only when web-core is present, which the surface needs
anyway.

Startup fails when any `public-paths` entry matches `/admin/v1/x` - checked by matching, not by string equality, so
`/**` is caught too.

*Alternative, chosen first and rejected during implementation.* A second `SecurityFilterChain` with
`securityMatcher("/admin/**")`. Two facts sank it: an error rendered in a filter chain goes through
`ProblemDetailAccessDeniedHandler` with `ludwig.security.problem-type-prefix`, while the PAT surface's goes through
web-core with `ludwig.web.problem.type-prefix`, so the "same problem type" requirement could not hold; and the
platform's chain is `@ConditionalOnMissingBean(SecurityFilterChain.class)` and backs off when a service declares its
own - the reason `PatCredentialGuard` is an interceptor too. The interceptor holds whichever chain is in place.

### 5. The reason header is checked after authorization

`Admin-Reason` is validated by the guard for `privileged` operations, after the grant check, so a caller without
the grant gets `403` regardless (the spec's scenario). Missing → `AdminReasonRequiredException`, a
`LocalizedException` (`ProblemStatus.BAD_REQUEST`, code `ludwig.admin.reason-required`), which web-core's pipeline
renders natively - the same way `PatCredentialNotPermittedException` is rendered, with no extra mapper. The reason is
placed on the request's `AdminInvocation`, which the audit step reads.

The old actuator design had two switches for destructive operations (a deployment flag and `confirm=true`). They
are replaced: *whether the environment permits it* is now whether any role is granted the `privileged` operation,
and *whether this moment is intended* is the mandatory reason. A reason is strictly more information than a boolean.

### 6. Audit is a helper, not a second sink

`AdminAudit.record(AdminInvocation, target, outcome)` builds an `AuditEvent` (category `admin`, action = operation id)
and hands it to the one `AuditSink`. It is called by module services, inside the transaction that made the change,
not by an interceptor - an interceptor cannot know the target id or whether the outcome was refused-by-state. No
`try/catch` around it; `AuditFailurePolicy` decides.

### 7. Tenant confinement is a value the module applies

`AdminInvocation.tenantScope()` returns a `TenantScope`: unconfined for a `SERVICE`, confined to exactly the caller's
tenant for a `USER` - including a user with no tenant, who then matches only untenanted resources, which is the
rule `SettingsAccessPolicy` already applies (`subject.tenantId().equals(principal.tenantId())`). A single-tenant
service, where neither callers nor resources carry a tenant, therefore needs no configuration. A module applies it as a QueryDSL predicate on its tenant-carrying tables. The data-scope machinery
(`DataAccessGuard`) was considered and rejected for this: its default access is `NONE` per unscoped resource, so every
administrative resource would need a `ludwig.security.data.policies` entry before an administrator saw anything,
and the rule here is fixed, not a policy.

### 8. One keyset page shape for administrative listings

`web-core` has no pagination primitive, and five areas in `starter-admin-endpoints` list unbounded tables. The
security starter's admin package provides `AdminPage<T>` (`items`, `next`) and `AdminCursor` (opaque, URL-safe
encoding of the last row's sort key and id, with a maximum page size of 200 enforced on decode). It lives here
rather than in `web-core` because every consumer is an administrative endpoint and already depends on this package;
moving it to `web-core` later, if a non-admin listing needs it, is a move down an existing edge.

### 9. Self-discovery

`GET /admin/v1/me/operations`, served by the security starter, guarded by the chain only. Descriptions resolve
through the module's i18n bundle in `UserPreferences.current()`'s locale.

### Dependency-direction check

This change adds no in-repo dependency. `security-spring-boot-starter` keeps its in-repo dependencies `audit-core`,
`cache-spring-boot-starter`, `pat-core`, `web-core-spring-boot-starter` (optional). Moving
`PatCredentialNotPermittedException` *into* the security starter: does `security-spring-boot-starter` already depend
on `pat-spring-boot-starter`? `project-index.json` `inRepoDependents` of `security-spring-boot-starter` contains
`pat-spring-boot-starter`, and the security starter's own closure is the four modules above, which does not contain
it - so the type moves down the existing edge, no cycle.

### POM changes

None. No root `pom.xml`, `ludwig-bom` or `ludwig-service-parent` change.

### Mechanical checks

| convention | owner | check |
|---|---|---|
| `/admin/` handlers carry `@AdminAccess` or `@AdminAuthenticated`, no second gate, mapped under `/admin/v1/`, no repository dependency | ArchUnit | new `RuleGroup.ADMIN` (`"admin"`, on by default) with violating fixtures |
| annotation ids and grant keys are catalogued | startup | `AdminOperationCatalog` validation, test per failure |
| tier matches behaviour | review | README operation table per module (stated in a comment on `AdminTier`) |
| endpoints absent without security | test | auto-configuration test per declaring module (change 2) |
| `public-paths` cannot open `/admin/` | startup | validation in `ResourceServerAutoConfiguration` |
| user-facing text not hard-coded | Checkstyle | existing `NonAsciiSourceText`; descriptions are bundle keys |

## Risks / Trade-offs

- [A service upgrades and every administrative call is `403`] → intended: there is no default grant. The startup
  log lists ungranted operations, and the migration note in the admin guide shows the block to add.
- [Three tiers are coarse; a support role that may retry outbox messages but not trigger reconciliation runs needs
  two areas granted differently] → that is exactly what per-area ids allow; finer than per-area-tier is a non-goal.
- [A service's own chain permits `/admin/**` anonymously] → the guard still refuses it with `401`; tested with a
  hand-written chain.
- [`Admin-Reason` free text may contain personal data] → it is an audit attribute and goes through the audit
  envelope's construction-time redaction; it is never logged.

## Migration Plan

Ships with `starter-admin-endpoints` in the next major release. A service adds a `ludwig.admin.grants` block and,
for administrative service principals, a `ludwig.identity.service-roles` entry. Rollback is the previous release;
there is no schema change in this change.
