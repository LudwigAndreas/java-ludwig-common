## 1. Gate and SPI (`security-spring-boot-starter`)

- [ ] 1.1 `ludwig.admin.approval.*` properties, startup validation (catalogued, privileged, store present), and the
      `AdminApprovalStore` SPI; verify `mvn -pl :security-spring-boot-starter test -Dtest=AdminStartupChecksTest`
- [ ] 1.2 Request capture (64 KiB cap, `413`) and the `202` short-circuit in `AdminAccessEvaluator`, with an in-memory
      test store; verify `mvn -pl :security-spring-boot-starter test -Dtest=AdminApprovalMvcTest`
- [ ] 1.3 Approval endpoints (list, read, approve, reject, withdraw), conditional transitions, re-dispatch with
      `requestedBy`/`approvedBy` in `AdminAudit`, and the envelope mapping; cover every `admin-approval` scenario in
      `AdminApprovalMvcTest`; verify as 1.2

## 2. Store (`job-core`)

- [ ] 2.1 The `create-approval` changeset (next free number) and `JdbcAdminApprovalStore`; verify
      `scripts/check_migrations.sh` and `mvn -pl :job-core verify -Dit.test=JdbcAdminApprovalStoreIT`
- [ ] 2.2 The expiry and interruption sweep under run lock `ludwig-admin-approval-expiry`; verify as 2.1

## 3. End to end

- [ ] 3.1 `notification-service`: an IT with `approval.required: [outbox.privileged]`, two administrators, discard
      requested, self-approve refused, peer-approve executes; verify `mvn -pl :notification-service -am verify`

## 4. Documentation

- [ ] 4.1 Both modules' READMEs (EN and RU), plus admin guide sections for enabling approval and break-glass (an
      operation left out of `approval.required`); regenerate both OpenAPI documents; remove the four-eyes item from the
      follow-ups list; verify heading-count parity, `scripts/api-docs.sh missing` and `openspec validate
      admin-four-eyes-approval --strict`

## 5. Verification gate

- [ ] 5.1 `scripts/gate.sh --change admin-four-eyes-approval security-spring-boot-starter job-core`
