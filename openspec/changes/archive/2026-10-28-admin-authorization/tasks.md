## 1. Operation catalog (security-spring-boot-starter)

- [x] 1.1 Add `ru.ludwigandreas.security.admin`: `AdminTier` (`READ`, `OPERATE`, `PRIVILEGED`, with the tier
  definitions from the spec in its javadoc and the "tier matches behaviour is a review rule" comment),
  `AdminOperation` (id, area, tier, description key; id grammar validated in the canonical constructor),
  `AdminOperationContributor`, `AdminOperationCatalog` (union, duplicate id fails naming both contributors).
  Unit tests for grammar, duplicates and union.
  Verify: `mvn -pl :security-spring-boot-starter -am test`
- [x] 1.2 Add `ludwig.admin.*` to `SecurityProperties` (or a sibling `AdminProperties` bound in the same
  auto-configuration): `enabled` (true), `grants` (`Map<String, List<String>>`), `allow-personal-access-tokens`
  (false). Startup validation: unknown grant key (message lists the area's catalogued ids), empty or blank role,
  and one INFO line listing ungranted operations. Tests for each, including the bracket-vs-YAML binding of a dotted
  key.
  Verify: `mvn -pl :security-spring-boot-starter -am test`
- [x] 1.3 Catalog validation that an area declaring any tier declares all three; failing-context test.
  Verify: `mvn -pl :security-spring-boot-starter -am test`
- [x] 1.4 `AdminPage<T>` and `AdminCursor` (opaque URL-safe encoding, page size capped at 200 on decode, a tampered
  cursor is `400`). Unit tests for round trip, cap and tampering.
  Verify: `mvn -pl :security-spring-boot-starter -am test`

## 2. Evaluation (security-spring-boot-starter)

- [x] 2.1 Add `@AdminAccess(String value)`, `@AdminAuthenticated`, and `AdminAccessEvaluator` (grant check on the
  resolved `LudwigPrincipal` roles, `Authorities.normalizeRole`, publishes `AuthorizationDeniedEvent` through the
  existing `AuthorizationEventPublisher`); teach `AuthorizationDeniedAuditListener` to take the method from a
  `HandlerMethod`. Unit tests: granted, ungranted, unprefixed role, no tier implication, token role claim ignored,
  denial event published.
  Verify: `mvn -pl :security-spring-boot-starter -am test`
- [x] 2.2 Startup check that every `@AdminAccess` id on a registered handler is catalogued (iterate
  `RequestMappingHandlerMapping` after refresh); failing-context test.
  Verify: `mvn -pl :security-spring-boot-starter -am test`
- [x] 2.3 `Admin-Reason` enforcement for `PRIVILEGED` after the grant check; `AdminReasonRequiredException`
  (`LocalizedException`, code `ludwig.admin.reason-required`); message keys in
  `i18n/ludwig-security-messages[_ru].properties`. Tests: missing reason `400`, ungranted without reason `403`,
  over-length `400`.
  Verify: `mvn -pl :security-spring-boot-starter -am test`
- [x] 2.4 `AdminInvocation` (bound per request: operation, reason, principal, `tenantScope()`) and `AdminAudit`
  building category-`admin` events for the one `AuditSink`, no try/catch. Unit tests for the event shape for `USER`
  and `SERVICE` actors and the three outcomes.
  Verify: `mvn -pl :security-spring-boot-starter -am test`

## 3. Administrative chain (security-spring-boot-starter)

- [x] 3.1 `AdminSurfaceGuard` (`HandlerInterceptor` on `/admin/**`): `401` unauthenticated, `403` partner, `403`
  PAT with `PatCredentialNotPermittedException` unless `ludwig.admin.allow-personal-access-tokens`; registered via
  `WebMvcConfigurer` only when web-core is present and `ludwig.admin.enabled` is not false. Integration test
  (`AdminSurfaceGuardIT`): each refusal, the PAT `type` equal to the PAT surface's, a service-declared chain that
  permits `/admin/**` still gets `401`, disabled surface unmapped. (Depends on 3.2 for the exception.)
  Verify: `mvn -pl :security-spring-boot-starter -am verify`
- [x] 3.2 Move `PatCredentialNotPermittedException` from `pat-spring-boot-starter` into the security starter (same
  problem type key) and add its `revapi.differences` entry in the root POM naming this change, the owner's decision
  and the new location.
  Verify: `mvn -pl :security-spring-boot-starter,:pat-spring-boot-starter -am verify`
- [x] 3.3 Fail startup when a `ludwig.security.public-paths` entry matches a path under `/admin/v1/`; test with
  `/admin/**` and `/**`.
  Verify: `mvn -pl :security-spring-boot-starter -am test`
- [x] 3.4 `GET /admin/v1/me/operations` (security starter controller, chain-guarded only, localized descriptions,
  empty list when nothing is held). Integration test with two contributed fixture operations.
  Verify: `mvn -pl :security-spring-boot-starter -am verify`

## 4. Rules (architecture-rules)

- [x] 4.1 Add `RuleGroup.ADMIN("admin", true)` and `AdminRules`: `/admin/` handler carries `@AdminAccess` or
  `@AdminAuthenticated`; no second
  authorization annotation; mapped under `/admin/v1/`; no repository dependency. Matched by annotation type name so
  `architecture-rules` takes no dependency on the security starter. One violating fixture per rule, and a vacuous
  pass for a module with no `/admin/` handler.
  Verify: `mvn -pl :architecture-rules -am verify`

## 5. Test support and documentation

- [x] 5.1 In `test-support-security`, a request post-processor that authenticates as a `USER` or `SERVICE`
  principal holding given roles, for use against the admin chain in module tests.
  Verify: `mvn -pl :test-support-security -am verify`
- [x] 5.2 Security starter README (+ `.ru.md`): the administrative surface section - tiers, grant block, bracket
  form for properties files, no default grant, reason header, PAT/partner refusal, tenant confinement, discovery
  endpoint. `docs/admin-guide.md` (+ `.ru.md`): replace §8's operator rows with the `/admin/v1/` surface and the
  upgrade note.
  Verify: `scripts/manifest.sh stale` and the i18n bundle key-set parity check in `mvn -q validate`

## 6. Verification gate

- [x] 6.1 `openspec validate admin-authorization --strict`
- [x] 6.2 `scripts/gate.sh --change admin-authorization security-spring-boot-starter architecture-rules test-support-security pat-spring-boot-starter`
  (expands to `mvn -q validate` and `mvn -pl :<module> -am verify` for each module and each in-repo dependent:
  `crud-service-example`, `export-spring-boot-starter`, `file-action-spring-boot-starter`,
  `grpc-client-spring-boot-starter`, `grpc-server-spring-boot-starter`, `identity-projection-spring-boot-starter`,
  `notification-service`, `pat-spring-boot-starter`, `test-support-security`, `user-settings-spring-boot-starter`,
  `asyncapi-spring-boot-starter`, `file-ingest-spring-boot-starter`, `messaging-db-spring-boot-starter`,
  `messaging-spring-boot-starter`, `object-storage-spring-boot-starter`, `odata-filter-spring-boot-starter`)
