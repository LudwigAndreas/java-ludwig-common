# Changelog

All notable changes to this platform are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

**One changelog for the whole reactor.** Every module shares one `${revision}` and is released
together, so thirty changelogs would be thirty copies of one list. An entry describes what changed
for a *consumer of the platform*, and names the module when that matters.

**Two locales, one heading set.** `CHANGELOG.ru.md` carries the same `##` headings as this file, in
the same order. `-Prelease` fails the build when either file has no heading for the version being
released — the same rule this repository already applies to `README.md` and `README.ru.md`.

**A release is a tag.** The version is computed by GitVersion from the git history and passed to
Maven as `-Drevision=<computed>`; `.mvn/maven.config` is only the fallback for a checkout with no
tags. Writing a version into that file does not cut a release, and adding a heading here does not
either.

## [Unreleased]

### Added

- **One caller-preference contract in `web-core`.** `ru.ludwigandreas.webcore.preference` resolves the
  caller's locale and zone from their stored setting, then the request's headers, then configuration,
  and publishes both into `LocaleContextHolder`. A mapper reaches it by naming
  `UserPreferenceFormatter` in `@Mapper(uses = ...)`, which MapStruct then selects by type - no mapping
  signature changes and no `@Mapping` annotation. Outside a mapper it is `UserPreferences.current()`,
  with `UserPreferences.bind()` to carry a captured caller's preferences onto a worker thread.
- **`user-settings-spring-boot-starter` contributes the stored half.** A service that declares
  `WellKnownSettings.LOCALE` and `TIMEZONE` now has them honoured by every response, rather than only
  stored. A value is treated as the user's choice only when its `SettingLayer` is more specific than
  `PLATFORM`, so a setting nobody has touched does not outrank the caller's `Accept-Language`.
- **Two ArchUnit rules**, in a new `RuleGroup.PRESENTATION`: nothing may call `Locale.getDefault()`,
  `TimeZone.getDefault()`, `ZoneId.systemDefault()` or `Clock.systemDefaultZone()`, and nothing may
  declare a second type holding the caller's locale-and-zone pair.

### Changed

- **`LocaleContextHolder.getTimeZone()` now answers the caller's zone on a request thread.** It
  previously answered `TimeZone.getDefault()` - the container's zone - because
  `AcceptHeaderLocaleResolver` is a plain `LocaleResolver` and never publishes a
  `TimeZoneAwareLocaleContext`. **Anything that formatted a date from Spring's holder changes
  output**: a `@JsonFormat` without an explicit zone, a `@DateTimeFormat` conversion, a date argument
  interpolated into a message. This is the intended fix and it is still a behaviour change worth
  reading before upgrading. `ludwig.web.preferences.enabled=false` restores the previous behaviour
  exactly.
- **`Content-Language` carries the region the caller asked for.** A request for `ru-RU` against a
  service supporting `ru` is now answered `Content-Language: ru-RU` rather than `ru`. The body is
  still rendered from the `ru` bundle, which `MessageSource` falls back to on its own; the region is
  kept because the JDK carries first-day-of-week and the number separators as *region* data, so
  narrowing `ru-RU` to `ru` hands a Russian user an American calendar.
- **`export-spring-boot-starter` renders a report in the caller's zone.**
  `SecurityReportCaller.zone()` was a hard-coded `ZoneId.of("UTC")`, which was five hours out on every
  row for a user in Yekaterinburg. `ReportCaller` gains `preferences()`, and `locale()` and `zone()`
  become `default` methods over it - a source-compatible addition for a caller, and a method an
  implementor of that interface must now supply. An explicit `timeZone` in a report request still wins.

### Fixed

- **Two ArchUnit rules that reported as passing while checking nothing.**
  `RuleGroup.CACHING`'s `noPrivateCaffeineCache` and `RuleGroup.KAFKA`'s
  `noPrivateDeadLetterRecoverer` were written as `noClasses().should(notDependOnClassesThat(..))`.
  `noClasses()` wraps the condition in ArchUnit's `never()`, which inverts every event, and the
  `ArchitectureConditions` helpers report only violations - so both produced zero findings over code
  that provably broke them. Both now use `classes().should(..)`, the reason is written at each site,
  and the new `PresentationRulesTest` asserts that a rule *names the offending class* rather than
  merely that it ran, which is the test shape that would have caught this.

## [1.1.0]

The **API compatibility baseline**. Everything published under this version is the floor every
later release is compared against, so a consumer can pin `1.1.0` and know what a `1.1.x` or `1.2.x`
is permitted to change.

### Added

- **An API-compatibility gate on every published library.** `revapi-maven-plugin` runs at `verify`
  for every module parented by the reactor root, comparing the module's compiled API against the
  newest release of the same coordinates in `ludwig.repo.releases`. The version increment is the
  permission: a major permits a binary-breaking difference, a minor permits a non-breaking one, a
  patch permits only an equivalent API. The two services get no comparison, because they are
  consumed as images rather than as jars.
- **A deprecation contract.** `@Deprecated` must declare both `since` and `forRemoval`, and must
  carry a Javadoc `@deprecated` tag naming the replacement. Enforced by two Checkstyle rules,
  `DeprecationWithoutSince` and `MissingDeprecated`, at `validate`.
- **Javadoc jars**, attached under `-Pci` beside the existing sources jars, for every library
  module. Malformed Javadoc — a broken `{@link}`, an unknown tag, a `@param` naming a parameter
  that does not exist — now fails the build; missing Javadoc remains the existing warning tier.
- **`CHANGELOG.md` and `CHANGELOG.ru.md`**, this pair, with an enforcer rule that fails a release
  build when either lacks a heading for the version being released.
- **`gitversion.yml` and a Jenkins version stage**, so the version is computed from the git history
  rather than typed into a file.
- **`scripts/check_image_pins.sh`**, wired into `scripts/gate.sh`: every container image reference
  in the `Jenkinsfile`, in YAML and in Markdown must carry a `sha256` digest, not a bare tag.

### Changed

- **A release is cut by tagging**, not by editing `.mvn/maven.config`. That file keeps a
  `-Drevision` value as the local, tag-less fallback only; a command-line user property beats it,
  which is the precedence CI relies on.
- **Every plugin taking part in the build declares a version.** `requirePluginVersions` is added to
  the enforcer in both the reactor root and `ludwig-service-parent`, and the plugins Maven and
  Spring Boot previously bound without one — `maven-jar-plugin`, `maven-resources-plugin`,
  `maven-clean-plugin`, `maven-install-plugin`, `maven-deploy-plugin`, `maven-site-plugin` — are
  pinned. Maven's own answer to an unpinned plugin is a warning that had been printed on every
  build of this repository without anyone acting on it.

### Fixed

- Twenty malformed Javadoc references across eleven modules, found by turning doclint on: three
  references to enum constants on a nested type, seven member-level `<h2>` headings where the
  implicit preceding heading is `<h3>`, three references to API that had been renamed or removed
  (`JiraClientBuilder#meterRegistry`, `request.IssueUpdate`, `AuthorityCache`), and several
  `{@link #getXxx()}` references to Lombok-generated accessors.
