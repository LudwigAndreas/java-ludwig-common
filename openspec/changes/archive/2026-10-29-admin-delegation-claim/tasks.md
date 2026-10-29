## 1. Envelope (`audit-core`)

- [x] 1.1 Add `via` to `Actor` (factories unchanged); update `AuditSinkConformanceTest`; verify `mvn -pl :audit-core
      -am verify`

## 2. Claim and actor (`security-spring-boot-starter`)

- [x] 2.1 `Delegation` on `LudwigAuthentication`; `DelegationClaims` parsing (absent, valid, nested to depth 5, too deep,
      malformed → 401); `ludwig.security.jwt.actor-claim`; unit tests including "`act.roles` grants nothing"; verify
      `mvn -pl :security-spring-boot-starter test -Dtest='DelegationClaimsTest,JwtPrincipalConverterTest'`
- [x] 2.2 `PrincipalActorResolver` fills `via`; extend its test; verify `mvn -pl :security-spring-boot-starter test
      -Dtest=PrincipalActorResolverTest`
- [x] 2.3 `ludwig.admin.human-callers.required-via` enforced in `AdminSurfaceGuard` for `USER` principals only; the
      three scenarios in `AdminSurfaceMvcTest`; verify `mvn -pl :security-spring-boot-starter test
      -Dtest=AdminSurfaceMvcTest`

## 3. Store and search (`audit-spring-boot-starter`)

- [x] 3.1 `0006-actor-via.sql` plus its include; map it on `AuditEventEntity`; verify `scripts/check_migrations.sh`
- [x] 3.2 `via` filter on `GET /admin/v1/audit/events`; extend the search integration test with the UI-yesterday
      scenario; verify `mvn -pl :audit-spring-boot-starter verify`

## 4. API baseline and documentation

- [x] 4.1 revapi accepted-difference entry for `Actor`'s constructor in the root `pom.xml`; verify `mvn -q validate`
      and `scripts/check_api_baseline.sh` (where the releases repository is reachable)
- [x] 4.2 READMEs (EN and RU) of the three modules, plus the `act` setup and break-glass advice in
      `docs/admin-guide.md` and `docs/admin-guide.ru.md`; regenerate both services' OpenAPI documents (the audit search gains a parameter)
      with `scripts/api-docs.sh`; remove the delegation item from the follow-ups list; verify heading-count parity per README
      pair and `openspec validate admin-delegation-claim --strict`

## 5. Verification gate

- [x] 5.1 `scripts/gate.sh --change admin-delegation-claim audit-core security-spring-boot-starter
      audit-spring-boot-starter`
