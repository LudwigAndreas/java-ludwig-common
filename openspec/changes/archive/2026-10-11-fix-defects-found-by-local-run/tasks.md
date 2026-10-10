## 1. Durable state

- [x] 1.1 Write `openspec/changes/fix-defects-found-by-local-run/state.json` with its `contract`
  block before the first edit. Proves:
  `python3 -c "import json;print(json.load(open('openspec/changes/fix-defects-found-by-local-run/state.json'))['contract']['done_when'])"`

## 2. Audience check (`security-spring-boot-starter`)

- [x] 2.1 Add a unit test: the post-processor given a non-Nimbus `JwtDecoder` returns a decoder
  that rejects a token for another audience and one with no audience, and accepts a token for a
  configured audience; with no audience configured the bean is returned unchanged. Confirm it fails
  first. Then implement design D1. Proves:
  `mvn -pl :security-spring-boot-starter -am test -Dtest='AudienceValidatingJwtDecoderPostProcessorTest' -Dsurefire.failIfNoSpecifiedTests=false`

## 3. Identity consumer (`identity-projection-spring-boot-starter`)

- [x] 3.1 Add a test that builds a context from the messaging and identity auto-configurations in
  Boot's own order with the consumer enabled and expects `identityKafkaListenerContainerFactory`.
  Confirm it fails first. Then implement design D2. Proves:
  `mvn -pl :identity-projection-spring-boot-starter -am test -Dtest='IdentityKafkaOrderingTest' -Dsurefire.failIfNoSpecifiedTests=false`

## 4. User-settings with no mode (`user-settings-spring-boot-starter`)

- [x] 4.1 Add a test: with a hot-reload factory bean present and no mode enabled the context
  starts and has no `SettingsDefaultsReloadListener`; with `ludwig.user-settings.enabled=false`
  likewise. Confirm it fails first. Then implement design D3. Proves:
  `mvn -pl :user-settings-spring-boot-starter -am test -Dtest='UserSettingsHotReloadInertTest' -Dsurefire.failIfNoSpecifiedTests=false`

## 5. Shared containers (`test-support`)

- [x] 5.1 Add an integration test: start a context importing `PostgresContainerConfiguration`,
  close it, and assert the container is running on the same port; call `stop()` and `close()` on
  `Containers.postgres()` and assert the same. Confirm it fails first. Then implement design D4.
  Proves: `mvn -pl :test-support -am test -Dtest='SharedContainerSurvivesContextIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false`
- [x] 5.2 Update `sources/test-support/README.md` and `README.ru.md`: a shared container cannot be
  stopped by a context or a test. Proves:
  `diff <(grep '^#' sources/test-support/README.md | sed 's/[^#].*//') <(grep '^#' sources/test-support/README.ru.md | sed 's/[^#].*//') && mvn -q validate`

## 6. Remove the workarounds (`notification-service`, test sources only)

- [x] 6.1 Turn `ludwig.identity.kafka.enabled` on in `application-localrun.yml`; make
  `LocalNotificationApplicationIT` assert it is on, and assert 401 for a token for another
  audience; drop the user-settings argument from `LaunchedApplication`. Proves:
  `git diff --exit-code a1bc188 -- services/notification-service/src/main && mvn -pl :notification-service -am verify -Dit.test=LocalNotificationApplicationIT -Dfailsafe.failIfNoSpecifiedTests=false`
- [x] 6.2 Update "What the local run found" in the service README, both locales. Proves:
  `grep -c "IDENTITY_PROJECTION_ENABLED" services/notification-service/README.md services/notification-service/README.ru.md; mvn -q validate`

## 7. Changelog

- [x] 7.1 Add `Fixed` entries under `## [Unreleased]` in `CHANGELOG.md` and `CHANGELOG.ru.md`,
  the audience one marked as a behaviour change. Proves:
  `diff <(grep '^## ' CHANGELOG.md) <(grep '^## ' CHANGELOG.ru.md) && grep -c "issuer-uri" CHANGELOG.md CHANGELOG.ru.md`

## 8. Verification gate

- [x] 8.1 Checkstyle and manifest. Proves: `scripts/manifest.sh stale && mvn -q validate`
- [ ] 8.2 The whole reactor, because `test-support` and `security-spring-boot-starter` are
  depended on by nearly every module. Proves:
  `scripts/gate.sh --change fix-defects-found-by-local-run --full`
- [x] 8.3 The change validates. Proves: `openspec validate fix-defects-found-by-local-run --strict`
- [x] 8.4 Write `openspec/changes/fix-defects-found-by-local-run/receipt.json`. Proves:
  `python3 -c "import json;json.load(open('openspec/changes/fix-defects-found-by-local-run/receipt.json'))"`
