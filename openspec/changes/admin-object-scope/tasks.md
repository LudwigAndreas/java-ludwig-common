## 1. Data scope (`security-spring-boot-starter`)

- [ ] 1.1 `ScopeDimension.OBJECT` in `BUILT_IN`, and `DataScope.admits(dimension, value)` with fail-closed handling of
      other dimensions; unit tests; verify `mvn -pl :security-spring-boot-starter test -Dtest='DataScopeTest,ScopeGrantTest'`
- [ ] 1.2 `DataScopePolicyValidator`: `admin.*` entries must be `ALL` or `NONE`, and the area must be catalogued;
      verify `mvn -pl :security-spring-boot-starter test -Dtest=DataScopePolicyValidatorTest`

## 2. Administrative evaluation (`security-spring-boot-starter`)

- [ ] 2.1 `@AdminObject`, evaluation in `AdminAccessEvaluator` (`403` or `404`, audited), `AdminObjectNotFoundException`,
      `AdminObjectScope.filter`; the four `admin-authorization` scenarios in `AdminSurfaceMvcTest`; verify `mvn -pl
      :security-spring-boot-starter test -Dtest=AdminSurfaceMvcTest`
- [ ] 2.2 ArchUnit rule `admin-object-is-a-path-variable` in `architecture-rules` (`RuleGroup.ADMIN`) with a violating
      fixture; verify `mvn -pl :architecture-rules -am verify`

## 3. Reconciliation (`reconciliation-spring-boot-starter`)

- [ ] 3.1 `@AdminObject` on every `{task}` parameter, and the task listing filtered through `AdminObjectScope`;
      `ReconciliationAdminIntegrationTest` covers own-task, other-task (`403`), read-scoped `404`, listing and
      no-policy cases; verify `mvn -pl :reconciliation-spring-boot-starter verify`

## 4. Documentation

- [ ] 4.1 `docs/data-scope-configuration.md`, the admin guide, and both modules' READMEs (EN and RU): the
      `admin.<area>` resource, a worked supplier-sync example, and the candidate areas; remove the per-object item from
      the follow-ups list and add "adopt object scope in `jobs` and `fileingest`"; verify heading-count parity and
      `openspec validate admin-object-scope --strict`

## 5. Verification gate

- [ ] 5.1 `scripts/gate.sh --change admin-object-scope security-spring-boot-starter reconciliation-spring-boot-starter
      architecture-rules`
