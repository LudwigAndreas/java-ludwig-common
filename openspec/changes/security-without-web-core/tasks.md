## 1. The failing check first (`security-spring-boot-starter`)

- [ ] 1.1 Add the `without-web-core` Surefire execution to the module's POM (web-core excluded from the test
      classpath, only `**/*WithoutWebCoreTest.java`; the default execution excludes that pattern) and
      `SecurityWithoutWebCoreTest`: every security auto-configuration starts; a `PRIVILEGED` operation called through
      `OperationEvaluator` without a reason is refused with `ReasonRequiredException`, and with a personal access token
      with `PatCredentialNotPermittedException`. Verify it fails against the current code:
      `mvn -pl :security-spring-boot-starter test` reports the startup failure, recorded in `state.json` `failures`.

## 2. The fix (`security-spring-boot-starter`)

- [ ] 2.1 Move `ludwigSecurityProblemMessageBundle` and `ludwigSecurityProblemMapper` into the nested
      `LudwigSecurityAutoConfiguration.WebCoreProblems`, guarded by `@ConditionalOnClass(name = ...)`, names and
      `@ConditionalOnMissingBean` unchanged. Verify `mvn -pl :security-spring-boot-starter test` - the startup case of
      `SecurityWithoutWebCoreTest` passes.
- [ ] 2.2 `SecurityRefusalException` (`ru.ludwigandreas.security.exception`): code, arguments, properties, no web-core
      type. `ReasonRequiredException` and `PatCredentialNotPermittedException` extend it with their codes, arguments and
      properties unchanged; `SecurityProblemMapper` maps them to `INVALID` and `FORBIDDEN`. Verify
      `mvn -pl :security-spring-boot-starter verify` - `SecurityWithoutWebCoreTest` passes in its execution, and
      `OperationEnforcementMvcTest` and `AdminSurfaceGuardTest` pass unchanged in what they assert.
- [ ] 2.3 README (EN and RU): web-core is optional and what a service without it gets; the refusals' types. Verify the
      heading-count parity of the two READMEs.

## 3. Dependents

- [ ] 3.1 `identity-source-directory-spring-boot-starter`: remove the test-scoped web-core dependency added as a
      workaround. Verify `mvn -pl :identity-source-directory-spring-boot-starter verify` and `scripts/manifest.sh build`
      then `scripts/manifest.sh stale`.
- [ ] 3.2 `pat-spring-boot-starter` and `grpc-server-spring-boot-starter`: the refusals render as before over both
      transports. Verify `mvn -pl :pat-spring-boot-starter verify` and
      `mvn -pl :grpc-server-spring-boot-starter verify -Dit.test=GrpcAdminAccessIT`.

## 4. Documentation

- [ ] 4.1 `CHANGELOG` (EN and RU) under `Unreleased` / `Fixed`; remove the web-core follow-up from
      `openspec/intents/admin-surface-follow-ups.md`. Verify heading-count parity and
      `openspec validate security-without-web-core --strict`.

## 5. Verification gate

- [ ] 5.1 `scripts/gate.sh --change security-without-web-core security-spring-boot-starter
      identity-source-directory-spring-boot-starter`, which runs every in-repo dependent, including both services.
