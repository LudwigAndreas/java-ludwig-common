Every task is confined to `reconciliation-spring-boot-starter`. `UNIT` below stands for
`mvn -pl :reconciliation-spring-boot-starter -am test` and `VERIFY` for
`mvn -pl :reconciliation-spring-boot-starter -am verify` (this module binds no failsafe: its integration tests are named `...IntegrationTest` and run under surefire, so a test named `...IT` would never run here).

## 1. State and schema

- [x] 1.1 Write `state.json` with its `contract` block (goal, output, constraints, `done_when` from `scripts/gate.sh --list reconciliation-spring-boot-starter` plus `openspec validate add-scope-enumeration-sync`) before any other edit; proven by `python3 -m json.tool openspec/changes/add-scope-enumeration-sync/state.json`
- [x] 1.2 Add `0012-create-scope-state.sql` creating `sync_scope_state` per design D3, with `dbms:postgresql` and a real `--rollback`; proven by `scripts/check_migrations.sh`
- [x] 1.3 Add `0013-create-scope-member.sql` creating `sync_scope_member` per design D3; proven by `scripts/check_migrations.sh`
- [x] 1.4 Add `0014-index-scope-state-due.sql` with the due-selection index on `sync_scope_state` and the per-scope generation index on `sync_scope_member`; proven by `scripts/check_migrations.sh`
- [x] 1.5 Include the three changesets in `reconciliation-changelog.xml` in ascending order; proven by `scripts/check_migrations.sh`
- [x] 1.6 Add the `SyncScopeState` and `SyncScopeMember` entities with optimistic locking on the state row, and their QueryDSL repositories (due selection, page-key lookup, bulk mark-seen update, sweep candidates, counts); proven by the existing `ddl-auto=validate` schema cross-check in `VERIFY`

## 2. SPI

- [x] 2.1 Add `EnumerationTask`, `ScopeSource` (with `single`), `MemberFeed`, `FeedRequest`, `MemberReconciler`, `MemberResult`, `LeftResult`, `MemberContext` and the bean annotation in `ru.ludwigandreas.reconciliation.api`, with javadoc stating the scope/member distinction and that `left` means leaving a scope; proven by `UNIT`
- [x] 2.2 Extend `ReconciliationArchitectureTest` so the new SPI types depend on no engine class and carry no Spring wiring, and add the rule that enumeration code does not reference `SoftDeleteEntity`; proven by `UNIT -Dtest=ReconciliationArchitectureTest`
- [x] 2.3 Add a unit test asserting `Fetcher` still permits exactly four shapes; proven by `UNIT -Dtest=FetcherShapesTest`

## 3. Configuration

- [x] 3.1 Add the `enumerations` and `enumeration-defaults` blocks to `ReconciliationProperties` and an `EnumerationSettings` record per design D9; proven by a binding unit test, `UNIT -Dtest=EnumerationPropertiesTest`
- [x] 3.2 Resolve task, then defaults, then built-in value per field for enumeration settings; proven by `UNIT -Dtest=EnumerationSettingsResolverTest`
- [x] 3.3 Add `EnumerationConfigurationValidator` (a sibling of `ReconciliationConfigurationValidator`, so that class's public constructor does not change) covering every case in the spec's "Invalid enumeration configuration prevents startup" requirement, reporting all problems in one pass; proven by `UNIT -Dtest=EnumerationConfigurationValidatorTest`
- [x] 3.4 Add the `enumeration-defaults` keys to `additional-spring-configuration-metadata.json` (the module runs no configuration processor, so this file is the only metadata); proven by `python3 -m json.tool sources/reconciliation-spring-boot-starter/src/main/resources/META-INF/additional-spring-configuration-metadata.json`

## 4. Engine

- [x] 4.1 Add the enumeration registry and registered-task holder, rejecting a name shared with a `SyncTask`; proven by `UNIT -Dtest=EnumerationRegistryTest`
- [x] 4.2 Implement the discover pass: keyset-page `ScopeSource`, insert missing `sync_scope_state` rows due now, never delete; proven by `VERIFY -Dtest=EnumerationDiscoveryIntegrationTest`
- [x] 4.3 Implement due selection and walk-kind choice per design D4 and D5, including never-completed-first ordering and lazy removal of a scope whose local row is gone; proven by `UNIT -Dtest=WalkPlannerTest`
- [x] 4.4 Implement the page applier in its own bean: one transaction per page covering lookup, bulk mark-seen, `apply`, membership insert/update and cursor write; proven by `VERIFY -Dtest=EnumerationWalkIntegrationTest`
- [x] 4.5 Implement the item-by-item fallback with failure recording, backoff and quarantine on the membership row, marking the walk not clean; proven by `VERIFY -Dtest=EnumerationFailureIsolationIntegrationTest`
- [x] 4.6 Implement walk completion: watermark advance from the high-water mark only on completion, `last_full_at` / `last_incremental_at`, `next_due_at`; proven by `VERIFY -Dtest=EnumerationWatermarkIntegrationTest`
- [x] 4.7 Implement the sweep with the evidence, plausibility and consecutive-miss guards, one transaction per `left` call, membership row deleted afterwards; proven by `VERIFY -Dtest=EnumerationSweepIntegrationTest`
- [x] 4.8 Apply the task's named rate limit to every feed call and bind the run and correlation ids as the existing walker does; proven by `UNIT -Dtest=EnumerationRateLimitTest`

## 5. Wiring and operations

- [x] 5.1 Register the new beans in `ReconciliationAutoConfiguration` behind `@ConditionalOnMissingBean`, and schedule a discover and a walk pass per enabled task in a sibling `EnumerationRuntime` under the task's run lease; proven by `VERIFY -Dtest=EnumerationRunLockIntegrationTest`
- [x] 5.2 Add `Category.SCOPE` to `ReconciliationAuditEvent` and record walk start, walk completion, member removal, sweep held and operator actions through `AuditSink` with no `try`/`catch` around `record`; proven by `VERIFY -Dtest=EnumerationAuditIntegrationTest`
- [x] 5.3 Add the metrics in design D10 to `ReconciliationMetrics` and both implementations, with gauges pulled from suppliers; proven by `UNIT -Dtest=EnumerationMetricsTest`
- [x] 5.4 Add the task-level actions `run`, `discover` and `requeue-members` to the existing operation and the scope-level actions `walk`, `preview`, `reset-scope`, `discard-sweep` and `confirm-sweep` as operations on `/actuator/reconciliation/{task}/{scope}` in `ReconciliationEndpoint`, with `confirm-sweep` under the existing two-key destructive rule, and enumeration tasks in the overview and detail reads; proven by `VERIFY -Dtest=EnumerationEndpointIntegrationTest`

## 6. Scenario coverage

- [x] 6.1 Integration test for a single fixed scope: first walk creates members, second walk with unchanged items never calls the integration; proven by `VERIFY -Dtest=EnumerationWalkIntegrationTest`
- [x] 6.2 Integration test for a scope per local row: a scope added later gets a full first walk while the older scope walks incrementally, and one member in two scopes leaves only one; proven by `VERIFY -Dtest=EnumerationScopesIntegrationTest`
- [x] 6.3 Integration test for a self-similar tree: one task whose `apply` writes the rows its `ScopeSource` reads, walked to a fixed point over a stub partner; proven by `VERIFY -Dtest=EnumerationTreeIntegrationTest`
- [x] 6.4 Integration test for an interrupted walk resuming from its checkpoint and for a failed page leaving the cursor on that page; proven by `VERIFY -Dtest=EnumerationResumeIntegrationTest`
- [x] 6.5 Integration test that no `sync_inbox_record` row is written for an enumeration task; proven by `VERIFY -Dtest=EnumerationWalkIntegrationTest`
- [x] 6.6 Integration test for a withheld sweep: empty partner response, held state visible, `confirm-sweep` refused without the setting, `discard-sweep` making the scope due; proven by `VERIFY -Dtest=EnumerationSweepIntegrationTest`

## 7. Documentation

- [x] 7.1 Add the enumeration section to `README.md` (when to use which task kind, quick start, configuration table, refreshable matrix, metrics, runbook entries for a held sweep and a quarantined member, the soft-delete note) and narrow "what this module is not for"; proven by `openspec validate add-scope-enumeration-sync` and `scripts/check_image_pins.sh`
- [x] 7.2 Mirror the same section in `README.ru.md`; proven by `scripts/check_image_pins.sh`

## 8. Verification gate

- [ ] 8.1 `scripts/check_image_pins.sh`
- [ ] 8.2 `scripts/check_api_baseline.sh`
- [x] 8.3 `scripts/check_migrations.sh`
- [x] 8.4 `scripts/check_build_metadata.sh`
- [x] 8.5 `mvn -q validate`
- [x] 8.6 `mvn -pl :reconciliation-spring-boot-starter -am verify`
- [x] 8.7 `openspec validate add-scope-enumeration-sync`
- [ ] 8.8 `scripts/gate.sh --change add-scope-enumeration-sync reconciliation-spring-boot-starter` (the module has no in-repo dependents, so there is no dependents step)
