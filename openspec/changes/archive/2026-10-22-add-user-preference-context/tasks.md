## 1. The contract in `web-core`

- [x] 1.1 `UserPreferences` — the record, non-null invariants, `current()`, `currentIfResolved()`,
  `bind()`, the derived accessors (`dateTimeFormatter`, `numberFormat`, `firstDayOfWeek`, `at`), and
  the `LocaleContextHolder` read that prefers our own `LocaleContext` over the
  `TimeZone`-to-`ZoneId` conversion.
  Verified by `mvn -pl :web-core-spring-boot-starter -am test -Dtest=UserPreferencesTest`.
- [x] 1.2 `UserPreferenceSource` — the per-dimension `Optional` SPI and the three order constants.
  Verified by the same test class compiling against it, and by
  `mvn -pl :web-core-spring-boot-starter -am test -Dtest=UserPreferenceResolutionTest`.
- [x] 1.3 `UserPreferenceResolver` — the per-dimension fold over ordered sources, with the
  supported-locale restriction applied after resolution (the `i18n-bundles` delta).
  Verified by `mvn -pl :web-core-spring-boot-starter -am test -Dtest=UserPreferenceResolutionTest`.
- [x] 1.4 `RequestHeaderPreferenceSource` and `ConfiguredPreferenceSource`, including the invalid-zone
  abstention.
  Verified by `mvn -pl :web-core-spring-boot-starter -am test -Dtest=UserPreferenceSourceTest`.
- [x] 1.5 `UserPreferenceLocaleContextResolver` — lazy memoising `TimeZoneAwareLocaleContext`,
  `setLocaleContext` refusal.
  Verified by `mvn -pl :web-core-spring-boot-starter -am test -Dtest=UserPreferenceLocaleContextResolverTest`.
- [x] 1.6 `UserPreferenceFormatter` — one method per source/target pair, with the comment stating why
  no check can see that constraint.
  Verified by `mvn -pl :web-core-spring-boot-starter -am test -Dtest=UserPreferenceFormatterTest`.
- [x] 1.7 `WebCoreProperties.Preferences` + `WebCorePreferenceAutoConfiguration`, registered in
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`; the
  the startup log names the active sources. **`WebCoreLocalizationAutoConfiguration` was not edited**:
  the new configuration is declared `before` it and registers the `localeResolver` bean name, and the
  existing bean is already `@ConditionalOnMissingBean(name = "localeResolver")`, so it backs off by the
  same ordering mechanism it uses to win over Spring Boot's own. The conditional edit this task
  described turned out to be unnecessary.
  Verified by `mvn -pl :web-core-spring-boot-starter -am test -Dtest=WebCorePreferenceAutoConfigurationTest`.
- [x] 1.8 `package-info.java` for `ru.ludwigandreas.webcore.preference`, stating the contract and the
  caller-versus-subject distinction, as `webcore.operation`'s does.
  Verified by `mvn -pl :web-core-spring-boot-starter -am verify` (javadoc doclint runs under the
  renderable configuration; a malformed tag fails it).
- [x] 1.9 An end-to-end MVC test: a request with `Accept-Language` and the timezone header reaches a
  controller whose mapper renders an `Instant`, and the rendered value is in the header's zone.
  Verified by `mvn -pl :web-core-spring-boot-starter -am verify -Dit.test=UserPreferenceContextIntegrationTest`
  (named `...IntegrationTest` so failsafe runs it at all — surefire excludes it).

## 2. The stored source in `user-settings`

- [x] 2.1 `StoredUserPreferenceSource` — `SettingsLookup.getAll(PrincipalRef)` once, both dimensions
  read from the one `ResolvedSettings`, abstention unless
  `layer.isMoreSpecificThan(SettingLayer.PLATFORM)`, abstention and a log on failure.
  Verified by `mvn -pl :user-settings-spring-boot-starter -am test -Dtest=StoredUserPreferenceSourceTest`.
- [x] 2.2 Its conditional registration — present only when the registry actually holds
  `WellKnownSettings.LOCALE` and `TIMEZONE`.
  Verified by `mvn -pl :user-settings-spring-boot-starter -am test -Dtest=StoredUserPreferenceRegistrationTest`.
- [x] 2.3 An integration test over the real resolution chain: a `USER`-layer stored locale beats an
  `Accept-Language` header, and a `DEFAULT`-layer one does not. An integration of the **chain**, not of
  the database - the two sources, the resolver and the layer test are the real classes and only
  `SettingsLookup` is stubbed, because what is under test is which source wins a dimension and
  standing up Postgres to answer a question about ordering would test nothing more.
  `OwnerModeIntegrationTest` already covers the read path against a real database.
  Verified by `mvn -pl :user-settings-spring-boot-starter -am verify -Dit.test=StoredPreferencePrecedenceIntegrationTest`.

## 3. `export` stops hard-coding UTC

- [x] 3.1 `ReportCaller.preferences()` added, `locale()` and `zone()` redefined as defaults over it;
  `SecurityReportCaller` returns the resolved pair.
  Verified by `mvn -pl :export-spring-boot-starter -am test -Dtest=CallIdentityTest`.
- [x] 3.2 `RenderContext` built from the resolved pair, with the explicit `RunReportRequest.timeZone`
  still winning. **No edit was needed**: `DefaultExecutionPlanner` already builds it from
  `request.locale()`/`request.zone()`, and `ReportRunController.toRequest` already falls back to
  `caller.locale()`/`caller.zone()`, so fixing `SecurityReportCaller` fixed the render context too.
  Verified by `mvn -pl :export-spring-boot-starter -am verify` - 198 tests, 0 failures.

## 4. The checks

- [x] 4.1 `RuleGroup.PRESENTATION` added to `architecture-rules`.
  Verified by `mvn -pl :architecture-rules -am test`.
- [x] 4.2 `noAmbientDefaultLocaleOrZone`, with each exemption carrying a comment naming why.
  Verified by `mvn -pl :architecture-rules -am test -Dtest=PresentationRulesTest`, which asserts both
  directions — a compliant fixture passes and a deliberately non-compliant one fails.
- [x] 4.3 `noSecondCallerPreferenceType`, with the javadoc stating the caller-versus-subject
  distinction and the per-subject exemption.
  Verified by the same test class, with `RecipientPreferences`-shaped and
  `UserPreferences`-shaped fixtures asserting the rule fires on one and not the other.
- [x] 4.4 Run the new rule group against the whole reactor and fix every real violation it finds; do
  not add an exemption to make a violation disappear.
  Verified by `mvn clean install` with the group enabled.
- [x] 4.5 **Added during 4.2, not planned.** `noClasses().should(<helper that reports only violations>)`
  checks nothing - ArchUnit's `never()` inverts each event - which was measured against the fixture, not
  reasoned about. Two pre-existing rules were inert that way and are fixed:
  `CachingRules.noPrivateCaffeineCache` and `KafkaRules.noPrivateDeadLetterRecoverer`.
  Verified by `mvn -pl :architecture-rules test` - 69 tests, 0 failures, including the three new ones
  that assert a rule *names* the offending class rather than merely that it ran.

## 5. Documentation

- [x] 5.1 `sources/web-core-spring-boot-starter/README.md` and `README.ru.md` — the new section, both
  locales, same heading set.
  Verified by `openspec validate add-user-preference-context` plus a diff showing both files changed.
- [x] 5.2 `sources/user-settings-spring-boot-starter/README.md` and `README.ru.md` — the stored source
  and the layer-precedence decision.
  Verified the same way.
- [x] 5.3 `sources/export-spring-boot-starter/README.md` and `README.ru.md` — the zone is the caller's.
  Verified the same way.
- [x] 5.4 `docs/harness-enforcement.md` — two enforced rows, three cannot-be-mechanised rows from the
  design's closing section.
  Verified by inspection against `design.md`; the document is the enforcement map and a missing row is
  the drift it exists to prevent.
- [x] 5.5 `CHANGELOG.md` and `CHANGELOG.ru.md` under `## [Unreleased]`, describing the behaviour change
  for a consumer — `LocaleContextHolder.getTimeZone()` changes meaning on a request thread, and
  `ReportCaller` gains a method.
  Verified by `mvn -q -Prelease -Drevision=9.9.9 validate` failing only on the version heading and not
  on a missing file.
- [x] 5.6 `CLAUDE.md` and `openspec/config.yaml` — the one-mechanism paragraph for caller preferences,
  beside the audit, operation and cache ones.
  Verified by inspection; this is the "encode every rule twice" guide half, whose check half is 4.2
  and 4.3.

## 6. The verification gate, in full

- [x] 6.1 `scripts/gate.sh --list :web-core-spring-boot-starter` — print the commands rather than
  trusting the list in the proposal.
- [x] 6.2 `mvn -q validate` — Checkstyle, every module.
- [x] 6.3 `mvn clean install` — because `web-core` has 14 in-repo dependents, which is the whole
  platform, a set of `-pl` builds is not narrower than a full build and would miss the aggregate
  coverage report.
- [x] 6.4 `python3 scripts/check_aggregate_report.py`.
- [x] 6.5 `openspec validate add-user-preference-context`.
- [x] 6.6 `openspec/changes/add-user-preference-context/receipt.json`, per `docs/agent-state.md`,
  with the real gate output, the test counts, the retries and anything unresolved.

No POM changes, so `scripts/manifest.sh build` is deliberately **not** in this list; `design.md`
states why. If a POM does change during implementation, this list is wrong and the manifest step is
added as a logged scope change in `state.json`.
