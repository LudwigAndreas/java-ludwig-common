## Context

See proposal.md - Why. This change builds on `admin-authorization` (`@AdminAccess`, `AdminOperationContributor`,
`AdminInvocation`, `AdminAudit`, `AdminPage`/`AdminCursor`, the `/admin/**` chain, `RuleGroup.ADMIN`). Facts that shape
it, verified in the code:

- `ReconciliationEndpoint` routes eleven actions through one `@WriteOperation` with an `action` switch plus a scope
  write operation with five more, and guards `cancel-job`, `release-lease` and `confirm-sweep` with
  `ludwig.reconciliation.endpoint.allow-destructive-operations` and `confirm=true`. `FileIngestEndpoint` guards its
  trigger with `ludwig.ingest.endpoint.allow-manual-run`. Neither id is in the default exposure list
  (`health,info,metrics,prometheus,loggers`).
- `outbox_message.status` is `VARCHAR(20)` with no check constraint; the poll index is partial on
  `status IN ('PENDING','FAILED')`. `outbox_message.last_error` and `consumer_offset.last_error` are free text.
- `job_run_lock` holds `owner`, `run_id`, `acquired_at`, `heartbeat_at`, `expires_at`; release nulls `owner`; renewal
  is conditional on `owner` and `run_id`. `SelfSchedulingLifecycle.runNow()` is non-reentrant and returns whether it ran.
- `audit_event` has `reason`, `on_behalf_of`, `actor_type`, no tenant. `audit-core`'s `Actor` is
  `(subject, principalType, displayName, onBehalfOf)`, built in eight places.
- `security_user` carries the tenant; `security_grant` and `security_partner` do not.
- `SettingsAccessPolicy` answers another tenant with `403` (`SettingsAccessDeniedException`).
- Five of the touched modules have no i18n bundle, and need one for their operation descriptions: `job-core`,
  `outbox-spring-boot-starter`, `reconciliation-spring-boot-starter`, `messaging-db-spring-boot-starter`,
  `identity-projection-spring-boot-starter`.

## Goals / Non-Goals

**Goals:** every module's controls behind one surface; a module's administrative code isolated in one package so
it can be read and reviewed as a unit.

**Non-Goals:** anything per-replica (see proposal Non-goals); showing content; changing a requester-scoped API's
semantics beyond PAT's self-service owner rule.

## Decisions

### 1. One package and one auto-configuration per module

Each module gets `ru.ludwigandreas.<module>.admin` holding the `<Area>AdminOperations` constants class (which is
also its `AdminOperationContributor`), `admin.service` holding the admin service, its views and its exception, and
`admin.web` holding the controller - the shared rules recognise the service and web layers by those package names
(`repositories-are-used-only-by-services`, `transactional-methods-are-in-the-service-layer`), and the service owns
its own filter types so that no controller reaches into the repository package. Queries live in the module's
`repository` package. Plus an
`<Area>AdminAutoConfiguration` in the module's existing configuration package - not in `admin`, because
`spring.configuration-classes-reside-in-config-packages` and `persistence.persistence-context-is-confined` both say
so, which the first area's architecture test showed. The auto-configuration is
`@ConditionalOnClass(AdminAccess.class)`, `@ConditionalOnBean(AdminOperationCatalog.class)`,
`@ConditionalOnWebApplication(type = SERVLET)`, and ordered `after` the security starter's auto-configuration - the
`starter-conditional-wiring` rule for beans conditional on another starter's beans. Controllers call a module service
(never a repository - `RuleGroup.ADMIN`); the services own transactions and call `AdminAudit` inside them.

*Alternative.* One `admin-spring-boot-starter` aggregating every area - rejected: it would depend on every module
it administers, and each module's operations would live far from the code whose invariants they must respect.

### 2. New optional edges, and the dependency-direction check

`job-core`, `outbox-spring-boot-starter`, `reconciliation-spring-boot-starter`, `file-ingest-spring-boot-starter`,
`messaging-db-spring-boot-starter` and `audit-spring-boot-starter` gain `security-spring-boot-starter` as an
**optional** compile dependency, and `job-core`, `outbox-spring-boot-starter`, `messaging-db-spring-boot-starter`
gain `spring-boot-starter-web` (optional, version from `spring-boot-dependencies`). Optional is the point: a service
that uses the module without the security starter gets no administrative endpoint (spec: fail closed), and gets no
security classes on its classpath.

> Does `security-spring-boot-starter` already depend, directly or transitively, on any of `job-core`,
> `outbox-spring-boot-starter`, `reconciliation-spring-boot-starter`, `file-ingest-spring-boot-starter`,
> `messaging-db-spring-boot-starter` or `audit-spring-boot-starter`? Its compile closure in `project-index.json`
> is `audit-core`, `cache-spring-boot-starter`, `pat-core`, `web-core-spring-boot-starter` - none of the six -
> so none of the six new edges closes a cycle. `audit-core` appears in it, which is why the actor-tenant change
> (decision 6) is made in `audit-core` and fed from the security starter, not the other way round.

`identity-projection-spring-boot-starter`, `export-spring-boot-starter` and `user-settings-spring-boot-starter`
already depend on the security starter (compile), `file-action-spring-boot-starter` optionally, and
`pat-spring-boot-starter` directly - no new edge.

`architecture-rules` is added at test scope to the touched modules that lack it - `job-core`,
`outbox-spring-boot-starter`, `reconciliation-spring-boot-starter`, `audit-spring-boot-starter`,
`identity-projection-spring-boot-starter`, `export-spring-boot-starter` - and every touched module gets (or extends)
an `ArchitectureRulesTest` subclass enabling `RuleGroup.ADMIN` over its `admin` package. `architecture-rules` has no
in-repo compile dependency, so no cycle.

### 3. Reconciliation: actions become resources, the engine is untouched

Each former action maps one-to-one onto an endpoint (table in `reconciliation-admin`) calling the same engine method
the actuator called (`TaskRunner`, `TaskStateService`, `EnumerationRunner`, `ScopeStateService`,
`RemoteJobSettlement`, `DatabaseQuota`). Run and backfill are submitted to the task's executor and answered `202`
with `OperationResponses`, the status header pointing at the task resource - there is no per-run row to point at,
and adding one is out of scope. "Already running on any replica" is answered from the task's run lease
(`job_run_lock`) if the task takes one, otherwise from `sync_task_state`; the implementer verifies which and
records it in the module README. The `requireDestructive` / `confirm` logic is deleted: the privileged tier and the
mandatory reason replace it (`admin-authorization` design §5).

### 4. Outbox: `DISCARDED` needs no migration

`DISCARDED` is appended to `OutboxStatus`. The column has no check constraint and the poll index's predicate
already excludes it, so no changeset is needed - the README says why, because the absence of a migration for a new
status will otherwise look like an omission. Retention: wherever the purge selects `DEAD_LETTER` it selects
`DISCARDED` too (the implementer greps the purge predicate). Retry writes a status-history row with `detail`
`admin retry`; the actor is in the audit event, because the history table has no actor column and adding one would
put a second actor record beside the audit trail. Bulk retry is one `UPDATE ... WHERE id IN (SELECT ... LIMIT 1000)`
in QueryDSL-JPA (`JPAUpdateClause` with a subquery), one history insert per moved row, one audit event for the call.

### 5. Jobs: lease truth from the table, run-now asynchronous

`GET /admin/v1/jobs` joins the registered `SelfSchedulingLifecycle` beans (name, schedule) with `job_run_lock` rows.
Run-now reads the lease first and answers `409` with the owner when held, else dispatches `runNow()` to the job's
own scheduler and answers `202`. The window between the read and the job's own acquisition is closed by the job's
lock, not by the endpoint: at worst the dispatched run declines and logs, which is the run-lock contract's
"declining is a normal outcome". Forced release is a new `RunLock.forceRelease(name)` with the same statement as
`release` minus the owner condition, outside the caller's transaction (`run-lock` requirement).

### 6. Audit: the actor carries its tenant

`Actor` gains a nullable `tenant` component; `PrincipalActorResolver` (security starter) fills it from the
principal; `audit_event` gains `actor_tenant VARCHAR(255)` with an index `(actor_tenant, occurred_at)` - changeset
`audit-0005-actor-tenant` in `audit-spring-boot-starter`. The other seven `new Actor(...)` call sites pass `null`.
Search is QueryDSL over `AuditEventEntity`, keyset on `(occurred_at, id)`. A search records its own event through
`AdminAudit` with the filter as attributes.

*Alternative.* Put the tenant in `attributes` - rejected: attributes are classified and redacted by the envelope,
and a confinement predicate over JSONB would be the only JSONB query on the platform.

### 7. Identity: writes through the existing entities

`SecurityPartnerEntity` and `SecurityGrantEntity` are already `AuditedEntity`; the area adds services that write
them and call `AdminAudit` (category `access`) in the same transaction, then evict the authority and data-scope
caches for the affected subject after commit - the same `TransactionSynchronization` the Kafka projection already
uses. Effective-authority inspection calls the resolver's uncached delegate. The README's "Known gap" paragraph is
removed.

### 8. Settings and PAT move, they are not rewritten

`AdminSettingsController` and `SettingsBackfillController` move to `ru.ludwigandreas.usersettings.admin`, re-mapped
and re-annotated; `SettingsAccessPolicy` keeps the tenant and service-principal rule and loses the authority check
(the grant now decides), and its refusal on the admin path maps to `404`. `PatManagementController` loses
`MANAGE_ANY` and keeps only own-subject behaviour; a new `PatAdminController` serves the `pat` area and refuses PAT
credentials itself, independent of the chain's flag.

### 9. Removals and their revapi entries

Each removed published element gets its own `revapi.differences` entry in the root POM, justification naming this
change, the owner's decision (removed in the major release without a deprecating release) and the replacement:
`ReconciliationEndpoint`, `ReconciliationActuatorAutoConfiguration`, `ReconciliationProperties.Endpoint` and its
accessor, `FileIngestEndpoint`, `FileIngestActuatorAutoConfiguration`, `FileIngestProperties.Endpoint` and its
accessor, `AdminSettingsController`, `SettingsBackfillController` (moved), the removed `UserSettingsProperties`
members, `PatAuthorities.MANAGE_ANY`, the `Actor` canonical constructor change, and
`ReconciliationAuditEvent.Category.OPERATOR` (only the actuator emitted it; removing it also shifts `SCOPE`'s
ordinal, which revapi reports separately), and `ReportRunController.ADMIN_AUTHORITY` (`ROLE_EXPORT_ADMIN`, a role a library named;
administrators now use the exports area).

### 10. Services and published documents

`notification-service` and `crud-service-example` each gain a `ludwig.admin.grants` block covering every area they
include (all three tiers to `NOTIFICATION_ADMIN` / `CATALOG_ADMIN`; `read` tiers also to `NOTIFICATION_SUPPORT`),
and their committed OpenAPI documents are regenerated with `scripts/api-docs.sh`. Admin operations are tagged
`admin` in the document.

### Mechanical checks

| convention | owner | check |
|---|---|---|
| admin code in `..admin..`, `@AdminAccess` on every handler, no second gate, no repository | ArchUnit | `RuleGroup.ADMIN`, enabled in each module's `ArchitectureRulesTest` |
| QueryDSL only in admin services | ArchUnit | existing `RuleGroup.PERSISTENCE` |
| no payload or content in admin DTOs | test | each area's integration test asserts the response JSON has no payload/content field; not mechanisable as a rule (a field name is not a fact about contents) - recorded in a comment on each DTO package's `package-info` |
| endpoints absent without security | test | per-module auto-configuration test (`ApplicationContextRunner` without the security starter) |
| new changesets well-formed | gate script | existing `scripts/check_migrations.sh` |
| bundle key parity for new bundles | Checkstyle/validate | existing parity check |

### POM changes

Module POMs only (the six optional edges, the optional web starter, `architecture-rules` at test scope). Root
`pom.xml`: `revapi.differences` entries only. No `ludwig-bom` change (no new third-party artifact; the web starter's
version comes from `spring-boot-dependencies`). No `ludwig-service-parent` change.

## Risks / Trade-offs

- [A service upgrades and its operators lose the actuator controls before granting] → the upgrade note in
  `docs/admin-guide.md` gives each service's grant block; the startup log lists ungranted operations.
- [Bulk retry floods a broker] → capped at 1000 per call, one audit event per call.
- [Showing `last_error` on outbox detail may disclose payload fragments] → shown only on the single-message view,
  under `outbox.read`, never in listings; the README states the risk so a service can withhold `outbox.read` from
  support roles.
- [Reconciliation run returns `202` but the status resource is the task, not the run] → the task view shows the
  current run's progress; a run table is a later change if operators need run history.
- [Actor-tenant is unknown for events stored before this release] → those are visible to service principals only;
  stated in `audit-trail-search`.
- [Forced lease release while the holder is mid-write] → the holder stops at its next renewal; work between renewals
  may run twice, which is already the run-lock contract on expiry.

## Migration Plan

Apply after `admin-authorization`. In each service: add `ludwig.admin.grants`; for administrative microservices add
`ludwig.identity.service-roles`; remove `reconciliation`/`fileingest` from `management.endpoints.web.exposure.include`
and any `ludwig.reconciliation.endpoint.*`, `ludwig.ingest.endpoint.*`, `ludwig.user-settings.access.admin-authority`,
`ludwig.user-settings.web.admin-base-path`; point administrative UIs at `/admin/v1/`. Schema: one additive changeset
(`audit_event.actor_tenant`), rollback drops the column. Rollback of the release is the previous major.

## Open Questions

- Whether reconciliation's "already running" check reads the run lease or `sync_task_state` (decision 3) - either
  satisfies the spec; recorded in the module README once verified.
