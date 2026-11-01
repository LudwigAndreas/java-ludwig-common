## 1. The model (`security-spring-boot-starter`)

- [x] 1.1 `security.operation`: `Operation`, `Sensitivity`, `Surface`, `OperationContributor` and `OperationCatalog`,
      enforcing the id grammar, uniqueness, sensitivity/action consistency and description resolution. Remove
      `AdminOperation`, `AdminTier`, `AdminOperationContributor` and `AdminOperationCatalog`. Verify
      `mvn -pl :security-spring-boot-starter test -Dtest='Operation*Test'` (grammar, duplicate, missing description,
      READ declaring write).
- [x] 1.2 `ludwig.security.roles` binding and `OperationGrants`:
      - role normalisation, scope parsing (reusing `ScopeGrant`), `includes` resolution with cycle and undefined-role
        detection, and refusal of `NONE` and wildcards;
      - startup failure naming `ludwig.admin.grants` and `ludwig.security.data.policies` when they are present;
      - both indexes.
      Remove `AdminGrants`, `ScopePolicies`, `PolicyDataScopeProvider` and `default-access`. Verify
      `mvn -pl :security-spring-boot-starter test -Dtest='OperationGrants*Test'`.
- [x] 1.3 `OperationAuthority`: `scope` as the union of table scopes and contributed providers, and `holds`.
      - The `DataScopeProvider` SPI takes an `Operation`.
      - `DataAccessGuard` takes an operation id.
      - Startup validation of dimensions against `DataScopeMapping` bindings, and `ALL`-only for resource-less
        operations.
      - A WARN listing operations no role holds.
      Verify `mvn -pl :security-spring-boot-starter test -Dtest='OperationAuthority*Test,DataAccessGuard*Test'`.
- [x] 1.4 Enforcement:
      - `@RequiresOperation` and `@RequiresAuthentication`, replacing `@AdminAccess` and `@AdminAuthenticated`;
      - `OperationEvaluator`, renamed from `AdminAccessEvaluator`;
      - the method-security `AuthorizationManager` for `BUSINESS` operations;
      - the `AdminSurfaceGuard` for `ADMIN` ones: reason `Operation-Reason`, the PAT rule from sensitivity,
        `ludwig.security.operations.allow-personal-access-tokens`;
      - object scope through the `OBJECT` dimension, replacing `AdminObjectScope`;
      - the surface/path startup check.
      Verify `mvn -pl :security-spring-boot-starter test` (every existing admin MVC and approval test rewritten to
      operation ids and green).
- [x] 1.5 Discovery:
      - `GET /me/operations`, replacing `AdminOperationsController` at its old path;
      - `GET /admin/v1/security/operations/{id}/holders` with the security area's `security.operation.read`.
      `ludwig.admin.approval.required` accepts only `ADMIN` `PRIVILEGED` operations, and `AdminAudit` records the
      precise id. Verify `mvn -pl :security-spring-boot-starter test` (the discovery and holders tests, with the
      approval eligibility test added).
- [x] 1.6 The security starter's README (EN and RU): the operation model, `ludwig.security.roles`, discovery,
      and the removed keys. Verify the heading-count parity of the two READMEs.

## 2. Platform areas on the new model (one module each, in dependency order)

Each task re-declares the area's operations exactly as the table in `design.md` lists them, moves its handlers and
controls to `@RequiresOperation`, rewrites its tests' grants to `ludwig.security.roles`, and updates its README (EN
and RU).

- [x] 2.1 `job-core`:
      - the jobs, replicas and caches areas;
      - `ReplicaControl.operationFor`/`possibleOperations` returning `Operation`;
      - the replica-control validator;
      - the approval store's operation ids.
      Verify `mvn -pl :job-core -am verify`.
- [x] 2.2 `outbox-spring-boot-starter`. Verify `mvn -pl :outbox-spring-boot-starter -am verify`.
- [x] 2.3 `hot-reload-spring-boot-starter` (the `hotreloadreload` control). Verify
      `mvn -pl :hot-reload-spring-boot-starter -am verify`.
- [x] 2.4 `rest-client-spring-boot-starter` (three controls). Verify
      `mvn -pl :rest-client-spring-boot-starter -am verify`.
- [x] 2.5 `audit-spring-boot-starter`. Verify `mvn -pl :audit-spring-boot-starter -am verify`.
- [x] 2.6 `identity-projection-spring-boot-starter`: nine operations; its per-subject grant provider on the new
      `DataScopeProvider` SPI. Verify `mvn -pl :identity-projection-spring-boot-starter -am verify`.
- [x] 2.7 `pat-spring-boot-starter`: the `pat` area. Its self-service `MANAGE_OWN` authority is untouched; that is
      `operations-starter-apis`. Verify `mvn -pl :pat-spring-boot-starter -am verify`.
- [x] 2.8 `messaging-db-spring-boot-starter`. Verify `mvn -pl :messaging-db-spring-boot-starter -am verify`.
- [x] 2.9 `file-ingest-spring-boot-starter`. Verify `mvn -pl :file-ingest-spring-boot-starter -am verify`.
- [x] 2.10 `file-action-spring-boot-starter`. Verify `mvn -pl :file-action-spring-boot-starter -am verify`.
- [x] 2.11 `export-spring-boot-starter`: the export area, and report definitions scoped by their read operation in
       `SecurityReportScopeResolver`. Verify `mvn -pl :export-spring-boot-starter -am verify`.
- [x] 2.12 `reconciliation-spring-boot-starter`: 15 operations, and object scope on `reconciliation.run.start` and
       `reconciliation.task.read` through `OBJECT`. Verify `mvn -pl :reconciliation-spring-boot-starter -am verify`.
- [x] 2.13 `user-settings-spring-boot-starter` (`settings.consent.read` split out). Verify
       `mvn -pl :user-settings-spring-boot-starter -am verify`.
- [x] 2.14 `grpc-server-spring-boot-starter`: `OperationInterceptor` reading `@RequiresOperation`, with
       `operation-reason` metadata (`GrpcMetadataKeys.OPERATION_REASON` in `grpc-core`, plus a parity test). Verify
       `mvn -pl :grpc-server-spring-boot-starter -am verify -Dit.test=GrpcAdminAccessIT`, rewritten to the new ids.
- [x] 2.15 `architecture-rules`: `RuleGroup.GRPC`'s javadoc and message name `@RequiresOperation`. Verify
       `mvn -pl :architecture-rules -am verify`.

## 3. Services

- [x] 3.1 `notification-service`:
      - declare the five business operations in `design.md` with their description bundle;
      - replace `ludwig.admin.grants` and `ludwig.security.data.policies` with `ludwig.security.roles`, granting the
        same effective access;
      - move its data-scope calls to operation ids.
      `@PreAuthorize` stays. Verify `mvn -pl :notification-service -am verify`; every existing authorization IT
      must pass unchanged in what it asserts.
- [x] 3.2 `crud-service-example`: the three product operations, `ludwig.security.roles`, data-scope calls, and the
      WATCHER provider on the new SPI. `@PreAuthorize` stays. Verify `mvn -pl :crud-service-example -am verify`.
- [x] 3.3 Regenerate both OpenAPI documents. Verify `scripts/api-docs.sh check`.

## 4. Documentation

- [x] 4.1 The admin guide (EN and RU):
      - the areas table gets operation ids instead of tiers;
      - a "Roles and operations" section with a complete `ludwig.security.roles` example;
      - the removed keys and the header rename.
      Add the `operations-starter-apis`, `operations-services` and `no-roles-in-code` follow-ups to
      `openspec/intents/admin-surface-follow-ups.md`. Verify heading-count parity of both guides and
      `openspec validate operations-catalog --strict`.

## 5. Verification gate

- [x] 5.1 `scripts/gate.sh --change operations-catalog security-spring-boot-starter`, which runs every in-repo
      dependent, including both services.
