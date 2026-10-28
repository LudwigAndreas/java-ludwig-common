## 1. Discovery endpoint

- [x] 1.1 `produces = MediaType.APPLICATION_JSON_VALUE` on `AdminOperationsController.operations`; assert the
      `Content-Type` in `AdminSurfaceMvcTest`; verify `mvn -pl :security-spring-boot-starter test
      -Dtest=AdminSurfaceMvcTest`

## 2. Description check

- [x] 2.1 `AdminHandlerValidator` fails startup on unresolved description keys, naming every one; add the
      forgotten-key case to `AdminStartupChecksTest`; verify `mvn -pl :security-spring-boot-starter test
      -Dtest=AdminStartupChecksTest`
- [x] 2.2 Neither service has a test of the administrative surface yet. Add `AdminDiscoveryIT` to
      `notification-service` and to `crud-service-example`, following each service's
      existing IT base class and naming; each calls `GET /admin/v1/me/operations` as an administrator and asserts no
      description equals its id. Verify `mvn -pl :notification-service -am verify` and `mvn -pl :crud-service-example
      -am verify`

## 3. Published documents

- [x] 3.1 Regenerate both OpenAPI documents; verify `scripts/api-docs.sh missing` exits 0 and
      `grep -A6 'me/operations' docs/api/*.openapi.yaml | grep -c 'application/json'` is 2

## 4. Follow-ups list

- [x] 4.1 Remove §3 from `openspec/intents/admin-surface-follow-ups.md` and reword the failsafe item as conditional;
      verify `openspec validate admin-surface-polish --strict`

## 5. Verification gate

- [x] 5.1 `scripts/gate.sh --change admin-surface-polish security-spring-boot-starter`, which runs `mvn -q validate`,
      the module and all 16 in-repo dependents, then `openspec validate`
