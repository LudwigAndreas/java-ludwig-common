## 1. hot-reload (`hot-reload-spring-boot-starter`)

- [ ] 1.1 POM: optional `job-core`, `security-spring-boot-starter`, `web-core-spring-boot-starter`;
      `ReloadableSourceRegistry` filled where sources are created; verify `mvn -pl :hot-reload-spring-boot-starter -am
      verify` and `scripts/manifest.sh build && scripts/manifest.sh stale`
- [ ] 1.2 `hotreload` operations and their bundle keys, `GET /admin/v1/hotreload/sources`, the `hotreloadreload` control,
      and an auto-configuration with a fail-closed test (with and without `job-core`); verify `mvn -pl
      :hot-reload-spring-boot-starter verify -Dit.test=HotReloadAdminIT`

## 2. rest-client (`rest-client-spring-boot-starter`)

- [ ] 2.1 POM: optional `job-core`, `security-spring-boot-starter`; `ClientTokenSources`; verify `mvn -pl
      :rest-client-spring-boot-starter -am verify`
- [ ] 2.2 `restclient` operations and their bundle keys, `GET /admin/v1/restclient/clients`, the controls
      `restclienttokenreset`, `restclientbreakers` and `restclientbreaker`, and an auto-configuration with a
      fail-closed test; verify `mvn -pl :rest-client-spring-boot-starter verify -Dit.test=RestClientAdminIT`

## 3. Services

- [ ] 3.1 `notification-service` grants `hotreload.*` to `NOTIFICATION_ADMIN` and `hotreload.read` to
      `NOTIFICATION_SUPPORT`; verify `mvn -pl :notification-service -am verify`
- [ ] 3.2 `crud-service-example` grants `restclient.*` and `hotreload.*` to `CATALOG_ADMIN`; verify `mvn -pl
      :crud-service-example -am verify`
- [ ] 3.3 Regenerate both OpenAPI documents; verify `scripts/api-docs.sh missing`

## 4. Documentation

- [ ] 4.1 READMEs (EN and RU) of both modules (Administration section, "a forced breaker resets on restart"), the
      admin guide areas table, and remove the per-replica item from the follow-ups list; verify heading-count parity
      and `openspec validate replica-controls --strict`

## 5. Verification gate

- [ ] 5.1 `scripts/gate.sh --change replica-controls hot-reload-spring-boot-starter rest-client-spring-boot-starter`
