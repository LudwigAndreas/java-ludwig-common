Prerequisite: `admin-authorization` is applied (its tasks are complete and its gate passed).

## 1. Actor tenant (audit-core, security-spring-boot-starter)

- [x] 1.1 `audit-core`: add a nullable `tenant` component to `Actor`; update its seven other construction sites to
  pass `null`; unit test that an actor without a tenant round-trips.
  Verify: `mvn -pl :audit-core -am verify`
- [x] 1.2 `security-spring-boot-starter`: `PrincipalActorResolver` fills `tenant` from the principal; unit test for a
  `USER` with a tenant, a `SERVICE` without one.
  Verify: `mvn -pl :security-spring-boot-starter -am test`

## 2. jobs area (job-core)

- [x] 2.1 POM: optional `security-spring-boot-starter`, optional `spring-boot-starter-web`, test-scoped
  `architecture-rules`; new bundle `i18n/ludwig-job-core-messages[_ru].properties` with the three operation
  descriptions.
  Verify: `mvn -pl :job-core -am validate`
- [x] 2.2 `RunLock.forceRelease(name)` per the `run-lock` requirement (outside the caller's transaction, no row
  deletion, reports whether it held anything); integration test `RunLockForceReleaseIT` for dead holder, live holder
  failing renewal, unheld lease.
  Verify: `mvn -pl :job-core -am verify`
- [x] 2.3 `ru.ludwigandreas.job.core.admin`: operations, contributor, controller (`GET /admin/v1/jobs`,
  `GET /admin/v1/jobs/locks`, `POST /admin/v1/jobs/{name}/run`, `POST /admin/v1/jobs/locks/{name}/release`),
  auto-configuration registered in `AutoConfiguration.imports`. `JobAdminIT`: `409` when held, `202` when free,
  release audited with former owner. Auto-configuration test: no endpoints without the security starter.
  `ArchitectureRulesTest` subclass enabling `RuleGroup.ADMIN`.
  Verify: `mvn -pl :job-core -am verify`

## 3. outbox area (outbox-spring-boot-starter)

- [x] 3.1 POM edges as 2.1 and a new `ludwig-outbox-messages` bundle; append `DISCARDED` to `OutboxStatus`; include
  it wherever the retention purge selects `DEAD_LETTER`; README note on why no changeset is needed.
  Verify: `mvn -pl :outbox-spring-boot-starter -am test`
- [x] 3.2 Admin service (QueryDSL; retry, capped bulk retry, discard, history rows, `AdminAudit`) and controller per
  `outbox-admin`. `OutboxAdminIT`: retry from `DEAD_LETTER` is published by the next poll; `409` from `PUBLISHED`
  and `PROCESSING`; bulk cap reports `more`; discarded row never claimed; no listing JSON contains `payload`,
  `headers` or `lastError`. Auto-configuration test and `RuleGroup.ADMIN` test.
  Verify: `mvn -pl :outbox-spring-boot-starter -am verify`

## 4. messaging area (messaging-db-spring-boot-starter)

- [x] 4.1 POM edges as 2.1 and a new bundle; `GET /admin/v1/messaging/consumers` per `messaging-db-admin`.
  `MessagingAdminIT`: lag equals head minus committed offset; no `lastError` in the response; non-`GET` unmapped.
  Auto-configuration test; `RuleGroup.ADMIN` enabled in the existing architecture test.
  Verify: `mvn -pl :messaging-db-spring-boot-starter -am verify`

## 5. reconciliation area (reconciliation-spring-boot-starter)

- [x] 5.1 POM: optional `security-spring-boot-starter`, test-scoped `architecture-rules`, new bundle.
  Verify: `mvn -pl :reconciliation-spring-boot-starter -am validate`
- [x] 5.2 `ru.ludwigandreas.reconciliation.admin`: every endpoint in `reconciliation-admin`'s three tables, calling
  the engine methods the actuator endpoint called; run/backfill `202` with `OperationResponses`; determine and
  record in the README how "already running" is decided.
  Verify: `mvn -pl :reconciliation-spring-boot-starter -am test`
- [x] 5.3 Delete `ReconciliationEndpoint`, `ReconciliationActuatorAutoConfiguration` (and its imports entry),
  `ReconciliationProperties.Endpoint`; port `EnumerationEndpointIntegrationTest` to `ReconciliationAdminIT` covering
  each former action, `400` on a privileged action without a reason, `/actuator/reconciliation` `404` with the id
  exposed. Auto-configuration test and `RuleGroup.ADMIN` test.
  Verify: `mvn -pl :reconciliation-spring-boot-starter -am verify`

## 6. fileingest area (file-ingest-spring-boot-starter)

- [x] 6.1 Optional security edge; admin package per `file-ingest-admin`; delete `FileIngestEndpoint`,
  `FileIngestActuatorAutoConfiguration`, `FileIngestProperties.Endpoint`; operation descriptions in the existing
  `ludwig-ingest-messages` bundle. `FileIngestAdminIT`: trigger `202` with status header to the run, `409` while
  running, run view without row contents, `/actuator/fileingest` `404`. Auto-configuration test; `RuleGroup.ADMIN`
  enabled in the existing architecture test.
  Verify: `mvn -pl :file-ingest-spring-boot-starter -am verify`

## 7. audit area (audit-spring-boot-starter)

- [x] 7.1 Optional security edge, test-scoped `architecture-rules`; changeset `0005-actor-tenant.sql`
  (`audit-0005-actor-tenant`, `dbms:postgresql`, rollback drops column and index) included from the module's root
  changelog; `JpaAuditSink` writes `actor_tenant`.
  Verify: `scripts/check_migrations.sh` and `mvn -pl :audit-spring-boot-starter -am test`
- [x] 7.2 Search service (QueryDSL, keyset on `(occurred_at, id)`, 30-day default window, page cap) and controller
  per `audit-trail-search`; each search audited. `AuditTrailSearchIT`: tenant confinement for `USER`, pre-column rows
  visible to `SERVICE` only, page size 1000 is `400`, the search's own event. Auto-configuration test and
  `RuleGroup.ADMIN` test.
  Verify: `mvn -pl :audit-spring-boot-starter -am verify`

## 8. identity area (identity-projection-spring-boot-starter)

- [x] 8.1 Test-scoped `architecture-rules`, new `ludwig-identity-messages` bundle; partner and grant admin services
  writing the existing entities with `AdminAudit` (category `access`) in the transaction and after-commit cache
  eviction; `If-Match` on partner `PUT`; dimension validated against the data-scope configuration.
  Verify: `mvn -pl :identity-projection-spring-boot-starter -am test`
- [x] 8.2 Controller per `identity-admin`, including uncached effective-authority inspection (audited) and tenant
  confinement by `security_user.tenant`. `IdentityAdminIT`: suspend refuses the partner within the TTL, revoked grant
  keeps its row, stale `PUT` is `412`, cross-tenant inspection `404`. Remove the README's "Known gap" paragraph.
  Auto-configuration test and `RuleGroup.ADMIN` test.
  Verify: `mvn -pl :identity-projection-spring-boot-starter -am verify`

## 9. exports area (export-spring-boot-starter)

- [x] 9.1 Test-scoped `architecture-rules`; admin package per `export-admin`, cancel reusing the requester's cancel
  path; `ExportAdminIT`: another requester's run listed without output, cancel `202`, terminal cancel returns the
  envelope, requester-scoped API still `404`s a non-requester. Auto-configuration test and `RuleGroup.ADMIN` test.
  Verify: `mvn -pl :export-spring-boot-starter -am verify`

## 10. fileactions area (file-action-spring-boot-starter)

- [x] 10.1 Admin package per `file-action-admin`, cancel reusing `FileActionService.cancel`; `FileActionAdminIT`: no
  row content in responses, cancelled deferred submission not applied. Auto-configuration test; `RuleGroup.ADMIN`
  enabled in the existing architecture test.
  Verify: `mvn -pl :file-action-spring-boot-starter -am verify`

## 11. settings area (user-settings-spring-boot-starter)

- [x] 11.1 Move `AdminSettingsController` and `SettingsBackfillController` into `..usersettings.admin` on the new
  paths (`consents/asof`); remove `access.admin-authority` and `web.admin-base-path`; `SettingsAccessPolicy` keeps
  the tenant and service-principal rule, loses the authority check, and its refusal on the admin path maps to `404`.
  Port the existing admin tests; add old-path-unmapped and cross-tenant-`404` cases. Remove the
  `architecture.rules.rules.admin.handler-declares-an-operation = false` deferral that `admin-authorization` added to
  this module's `architecture-rules.properties`, so the whole `RuleGroup.ADMIN` runs here.
  Verify: `mvn -pl :user-settings-spring-boot-starter -am verify`

## 12. pat area (pat-spring-boot-starter)

- [x] 12.1 Remove `PatAuthorities.MANAGE_ANY` and the other-owner branches of `PatManagementController`; add
  `PatAdminController` per the `personal-access-token` delta, refusing PAT credentials itself. `PatAdminIT`:
  self-service other-owner `403` and audited, admin issue for `bob` returns the secret once, admin revoke within the
  revocation window, PAT caller `403` even with `ludwig.admin.allow-personal-access-tokens=true`.
  Verify: `mvn -pl :pat-spring-boot-starter -am verify`

## 13. Services

- [x] 13.1 `notification-service`: `ludwig.admin.grants` for every included area (all tiers to `NOTIFICATION_ADMIN`,
  `read` tiers also to `NOTIFICATION_SUPPORT`); remove any `reconciliation`/`fileingest` exposure; regenerate the
  committed OpenAPI document.
  Verify: `scripts/api-docs.sh refresh`, then `scripts/api-docs.sh check` and `mvn -pl :notification-service -am verify`
- [x] 13.2 `crud-service-example`: the same with `CATALOG_ADMIN`; regenerate its document.
  Verify: `scripts/api-docs.sh refresh`, then `scripts/api-docs.sh check` and `mvn -pl :crud-service-example -am verify`

## 14. Removals record and documentation

- [x] 14.1 Root `pom.xml`: one `revapi.differences` entry per element in design §9, each naming this change, the
  owner's decision and the replacement.
  Verify: revapi runs in each module's `verify`, so `mvn -pl :reconciliation-spring-boot-starter,:file-ingest-spring-boot-starter,:user-settings-spring-boot-starter,:pat-spring-boot-starter,:audit-core -am verify`
  reports no unlisted difference (and `scripts/check_api_baseline.sh` reports each baseline as resolvable or absent)
- [x] 14.2 Every touched module's README (+ `.ru.md`): an operations table (id, tier, endpoints, what it does) and
  removed-property notes. `docs/admin-guide.md` (+ `.ru.md`): §8 rewritten for `/admin/v1/`, the per-service grant
  blocks and the upgrade steps from design Migration Plan.
  Verify: `scripts/manifest.sh stale` and the bundle key-set parity check in `mvn -q validate`

## 15. Verification gate

- [x] 15.1 `openspec validate starter-admin-endpoints --strict`
- [x] 15.2 `scripts/gate.sh --change starter-admin-endpoints audit-core security-spring-boot-starter job-core outbox-spring-boot-starter messaging-db-spring-boot-starter reconciliation-spring-boot-starter file-ingest-spring-boot-starter audit-spring-boot-starter identity-projection-spring-boot-starter export-spring-boot-starter file-action-spring-boot-starter user-settings-spring-boot-starter pat-spring-boot-starter notification-service crud-service-example`
  (expands to `mvn -q validate`, `scripts/check_migrations.sh`, and `mvn -pl :<module> -am verify` for each module and
  every in-repo dependent listed in proposal.md's Impact table)
  Result: every Maven command green (one reactor verify over the 26 gate modules, resumed twice: after the
  AsyncAPI scanner fix, and past one StubFaultIT timing flake that passed on rerun); check_migrations,
  check_build_metadata, check_protos, api-docs missing/check, mvn -q validate and openspec validate pass.
  Two known exceptions, unchanged from admin-authorization and pending the owner's acceptance for this change:
  check_image_pins (archived-prose false positive) and check_api_baseline (v2.0.0 releases repository unreachable).
