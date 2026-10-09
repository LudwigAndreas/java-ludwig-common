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

- **`odata-filter-spring-boot-starter`: filter-policy discovery.** A client can ask what it may filter on
  instead of finding out by sending filters and reading the rejections - which was the only alternative, and
  is the behaviour `odata.filter.rejected` is documented as an alerting signal for. `GET <base-path>/<entity>`
  returns the property paths the caller may name, each one's type, the operators permitted on it, whether it
  is sortable, and the limits the server enforces. Off until `odata.filter.metadata.base-path` is set, and
  per-entity opt-in on top of that via `@FilterPolicy(metadataName = ...)`; an entity nobody published answers
  404, not an empty document. The document is filtered per caller - a path its roles do not allow is absent
  rather than listed as forbidden, and no role name appears in it - and it is a projection of the resolved
  policy, so it cannot drift from what the query path accepts. It is deliberately not called `$metadata` and
  does not claim to be CSDL.
- **`odata-filter-spring-boot-starter`: `odata.filter.metadata.served`**, a counter tagged by entity, so the
  clients that asked properly can be told apart from the ones probing.
- **`audit-core`: the `query` audit category.** A caller's read of a filterable collection, kept distinct
  from `access`, which records authorization decisions. "Was alice permitted to search" and "what did alice
  search for" are different questions, and an investigation normally needs to join them rather than find
  them merged.
- **`odata-filter-spring-boot-starter` now has an architecture test.** It had none, so the shared ArchUnit
  rule library - including the whole `audit` group, whose subject is this module - had never been applied to
  it. Two of its rules are switched off there as named debt, with the reason at the point of the disable:
  `cycles.modules-are-free-of-cycles` and
  `configuration-properties.configuration-properties-are-validated`.
- **`architecture-rules`: `audit.audit-types-produce-audit-events`.** Fails the build when a type in a
  module's `audit` package produces no `AuditEvent`. The two existing audit rules only caught a module that
  declared something *extra* - a second SPI, a second logger - and not one that declared nothing and also
  called no sink, which is how this module's trail reached nothing while the build stayed green.
- **`odata-filter-spring-boot-starter`: one call runs a filtered, ordered, counted page.**
  `ODataQueryExecutor` applies the predicate, the ordering, the offset, the limit and - unless the caller
  sent `$count=false` - the count, and `ODataSearch` lets a repository contribute its own predicates
  (where a data scope goes) and shape the content query (where a fetch join goes) without writing any of
  those four steps. Both example services lost a `PathBuilder` field, an `orderSpecifiers(Sort)`, a
  reflective `orderSpecifier(String, boolean)` with an unchecked-cast suppression, a
  `PageableExecutionUtils` call and a private `count` - which they had been carrying identically. The
  executor also refuses a query root whose alias differs from the one the predicate is built against:
  that mismatch never failed in JPQL, it cross-joined the table to itself and returned rows that were
  quietly wrong.
- **`odata-filter-spring-boot-starter`: `ODataPaths` exposes the dynamic path walk** for a repository
  that must drive its own query and still needs the `$orderby` ordering expressions.
- **`odata-filter-spring-boot-starter`: `$count` is implemented.** `$count=false` skips the count query
  and the response then carries no total. The module previously documented this option as deliberately
  ignored, on the grounds that a flag nothing could act on bought the caller nothing - which was true
  until something here executed the query.
- **`odata-filter-spring-boot-starter`: `ODataQueryOptions`** carries the five query options as one
  springdoc-describable controller parameter, replacing five `@RequestParam` declarations per endpoint and
  the per-service `XSearchCriteria` records that were structurally identical across services.
- **`web-core-spring-boot-starter`: `PageResponse.offset`.** The absolute row offset applied, so a caller
  paging by an unaligned `$skip` can read its own position back out of the response.
- **`architecture-rules`: `persistence.dynamic-paths-are-not-hand-built`.** Fails the build when a
  service builds a QueryDSL `PathBuilder` for a caller-supplied property name instead of taking the
  resolution from the module that validated it.
- **`file-action-spring-boot-starter`: the platform's one path for a user-submitted file that performs a
  business action.** A person drags a spreadsheet in and orders are created. A service writes a typed
  `RowBinding`, one of two sealed handler shapes and a block of YAML; the module owns the upload edge, the
  size ceiling enforced while the body is read, magic-byte format detection, storage before parsing, the
  content-hash idempotency claim, the scanner seam, the bounded-memory spreadsheet reader, the
  `UPLOADED/VALIDATED/APPLIED` lifecycle on `web-core`'s operation envelope, the commit policy, the paged
  row rejects and downloadable annotated workbook, a generated template, deferred execution under a lease,
  retention and audit. Reading an untrusted XLSX goes through POI's `XSSFReader` SAX path only, under
  compression-ratio, entry-count and inflation ceilings decided from the ZIP directory before any XML is
  parsed; `.xls` is refused, because `HSSFWorkbook` has no streaming mode and supporting it would make the
  heap a function of a user-supplied file. Sized for an interactive upload - 25 MiB and 100,000 rows by
  default - and a larger file is refused with a message naming `file-ingest-spring-boot-starter`.
- **Two declarations a deployment must make for itself.** `commit-policy` has no default, because
  `PER_ROW` would silently apply two thirds of a journal entry and `ALL_OR_NOTHING` would refuse four
  hundred contacts over one mistyped address, and which is wrong depends on the domain; an action without
  one does not start. `ludwig.file-action.scanning.mode` defaults to `required`, so an application with no
  `FileScanner` bean does not start either - having no scanning is a choice to be made out loud rather than
  a gap nobody notices.
- **`RuleGroup.UPLOADS` in `architecture-rules`.** `MultipartFile` may appear only in
  `file-action-spring-boot-starter`. Before this change there was not one in the repository, and the first
  service to need an upload would have written the predictable mistakes: no ceiling before the body is
  read, `getBytes()`, a DOM workbook read, and a 400 carrying three thousand row errors.
- **A working `product-import` in `crud-service-example`.** A product sheet becomes products through
  `ProductService` - an import is a different way in to an action the service already performs, not a
  second implementation with a table of its own.
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

- **`notification-service`: in-app notifications, the one passive channel.** A recipient who wants no
  email, chat or webhook can still be told something. `IN_APP` is a delivery channel like the others -
  same request contract, same template layout, same preference and suppression handling - but it is
  classified `PASSIVE`, and that classification is a mandatory constructor argument on `ChannelType`, so
  a new channel cannot be added without deciding it. Being passive means it does not interrupt: quiet
  hours do not defer it, because there is nothing to wake anybody up. Delivery settles the moment the
  item is written, since there is no provider to accept it. The inbox is the reader: list, unread count,
  mark one or all read, mark seen, and dismiss, each scoped to the caller and never taking an owner
  parameter - so one caller cannot read another's inbox by changing a path. A foreign item and a missing
  one are both 404, deliberately indistinguishable. Every transition is idempotent.
- **`notification-service`: platform announcements.** One announcement addressed to everybody, or to
  everybody holding a role, rather than a notification per person. The distinction is the design: a
  notification fans out on write (N rows, one reader each), an announcement fans out on read (one row, N
  readers), so publishing to the whole organisation is a constant number of statements and so is purging
  it. A recipient sees the announcements their roles allow, dismisses them individually, and a dismissal
  is a row that exists only for the people who actually dismissed. Content is stored per supported
  locale. The audience is resolved once at publish against a configured allowlist, so a role nobody
  authorized cannot be targeted, and the role codes are normalized at that point - a role-targeted
  announcement that matched nobody was the defect that found. A category may also fan out by email,
  which is a long-running operation reported through the platform's one `OperationResponse` envelope,
  cancellable cooperatively, answering 202 because the stop is only requested and committed deliveries
  are not recalled. Visibility is a window, and retention is anchored to the end of it rather than to
  creation, so an announcement published for next quarter is not purged before it appears and one still
  showing never vanishes from under the people reading it.
- **Both services publish their OpenAPI document into the tree**, at
  `docs/api/<artifactId>.openapi.yaml`, generated from the service's real application context rather
  than written by hand. The point is reviewability: a service's HTTP surface otherwise exists only
  inside a running process, so a change that widens a response, drops a field or alters a status code
  shows a reviewer nothing. An `ApiDocumentIT` per service regenerates the document at `verify` and
  fails the build when the committed copy disagrees with what the service serves; an ordinary build
  never rewrites it, so refreshing is deliberate and the diff is always asked for. Each document is
  canonicalized first - every object's members sorted, `servers` stripped - because springdoc's
  handler-walk order is not promised to be stable and unsorted output would produce diffs that are pure
  reordering. These documents describe what the services currently serve; they are explicitly not a
  compatibility baseline, which remains `scripts/check_api_baseline.sh`'s job for the jars.
  `crud-service-example` gained springdoc to make this possible, which also activated
  `odata-filter-spring-boot-starter`'s customizer there for the first time - so the reference service's
  search endpoint now documents its five OData query options, as the `odata-query-contract` capability
  already required but nothing could check.

### Changed

- **BREAKING for a database that already applied these changesets - every Liquibase changeset in the
  platform is now formatted SQL.** All 84 changesets across 13 modules moved out of Liquibase's XML
  change vocabulary into `--liquibase formatted sql` files, one changeset per file, named
  `NNNN-<slug>.sql` with the changeset id `<prefix>-NNNN-<slug>` and the author `ludwig-<prefix>`, so
  the filename and the `DATABASECHANGELOG` row cannot disagree. Each module keeps one XML root
  changelog holding `<include>` elements and comments only - it stays XML because Liquibase 4.27's
  formatted-SQL parser has no include directive. **Every root changelog keeps its existing path**, so
  no consumer's `<include>` line, `spring.liquibase.change-log` setting or module property changes.
  What a consumer sees instead is the DDL itself, in the file, rather than a vocabulary that generates
  it - and 46 of those changesets already wrapped a raw `<sql>` block, so the repository was half SQL
  already with no rule saying which to use.

  **Four changeset-id namespaces were corrected** in the same pass, because the author is now derived
  from the prefix: `ingest-` became `file-ingest-`, `usrset-` became `user-settings-`,
  `notification-service`'s `0004-N-` scheme became `notification-00NN`/`ludwig-notification` like its
  five siblings, and the reconciliation test changelog became `reconciliation-test-`. Sequence numbers
  were renumbered to be unique and ascending within each module, which closed
  `crud-service-example`'s two changesets numbered `003` and two numbered `004` and
  `reconciliation`'s `004b`.

  **If a database has already applied the XML changesets**, its next startup fails on a checksum
  mismatch rather than a corrupt schema. Recovery is `liquibase clearChecksums` followed by a normal
  `update`; for the four renamed namespaces above, the `DATABASECHANGELOG` rows also need their `ID`,
  `AUTHOR` and `FILENAME` updated to the new values, or those changesets re-run. This change was made
  on the recorded basis that no such deployment exists.

- **Every changeset now declares `dbms:postgresql` and an explicit rollback.** The rollback is either
  real SQL or `--rollback NOT REQUIRED`, which Liquibase parses to the same empty rollback as XML's
  `<rollback/>`; 37 of the 84 previously declared none at all, so nothing distinguished "irreversible
  by nature" from "nobody thought about it". The dialect declaration makes a non-PostgreSQL target
  skip a changeset rather than fail halfway through the schema. `pat-0002`'s rollback now also drops
  `ux_ludwig_pat_key_id`, which the XML version left behind.

- **BREAKING - `odata-filter-spring-boot-starter`: `ODataFilterProperties` moves from `..config..` to
  `..properties..`.** Only the import changes. It was the single class holding the module's entire
  package-cycle problem: `core`, `web` and `policy` each read the bound properties while the wiring in
  `config` references every package in the module, and those three inbound edges closed **twenty** distinct
  cycles through `config` - every cycle the module had. One move removed all twenty. The auto-configuration
  classes deliberately stay in `config`, because an auto-configuration class's fully-qualified name is
  configuration API: a deployment switches one off with `spring.autoconfigure.exclude=...` in YAML, which no
  compiler checks, so renaming that package would break an exclusion silently at runtime.
- **`odata-filter-spring-boot-starter`: bound properties are validated at startup.** `maxDepth`,
  `maxPageSize`, `defaultPageSize`, `maxNestedPropertyDepth` and `maxExpressionLength` must each be at least
  1, and none of them was checked before. A `max-page-size` of 0 was accepted and then failed *every* query
  at the first request, with a message naming `$top` - pointing an operator at the caller rather than at the
  line of configuration that caused it. `hibernate-validator` is taken as an **optional** dependency, so it
  does not reach a consumer; every in-repo consumer already ships one.
- **`odata-filter-spring-boot-starter`: its `ArchitectureTest` now disables nothing as debt.**
  `cycles.modules-are-free-of-cycles` and
  `configuration-properties.configuration-properties-are-validated` were both switched off when the module
  first got an architecture test; both are enabled.
- **`odata-filter-spring-boot-starter`: a filter application is recorded to `audit-core`'s single
  `AuditSink`**, under the `query` category, instead of being published only as a Spring application event
  that each consumer had to forward itself. The event is still published as an in-process hook. Whether a
  sink outage fails the query is `AuditFailurePolicy`'s decision, resolved from the deployment's
  configuration - this module wraps the sink call in no `try`/`catch`.
- **BREAKING - `odata-filter-spring-boot-starter`: `ODataQueryExecutor`, `ODataSearch` and `ODataPage` move
  from `..querydsl..` to `..execution..`.** `core` already depended on `querydsl`, so the executor
  depending on `core` from inside `querydsl` was a package cycle - introduced by the previous change and
  caught by this one's new architecture test. Only the import changes.
- **`odata-filter-spring-boot-starter`: `FilterSummary` lives in `..ast..`**, not `..audit..`. It describes
  a filter rather than an audit concept, and the new audit rule is right to insist that a type in a
  module's audit package produces the platform envelope.
- **BREAKING - `odata-filter-spring-boot-starter`: `ODataFilterService.parse` takes an
  `ODataQueryOptions`** instead of four positional parameters, two `String` and two `Integer`. The old
  signature let a call site transpose `filter` and `orderBy`, or `top` and `skip`, and still compile.
- **BREAKING - `odata-filter-spring-boot-starter`: `ODataQuery.predicate()` returns
  `Optional<Predicate>`** and is empty when the caller supplied no `$filter`, where it used to return
  `Expressions.TRUE`. An always-true predicate is indistinguishable from a caller's real one, which is
  why `export-spring-boot-starter` was appending an unconditional `AND true` to every unfiltered report.
- **BREAKING - `odata-filter-spring-boot-starter`: `ODataQueryArgumentResolver` and
  `odata.filter.web.argument-resolver-enabled` are removed.** The resolver was deprecated and off by
  default with no replacement; `ODataQueryOptions` is the replacement. Its request-reading half is kept
  verbatim, including the non-`$`-prefixed parameter aliases.
- **BREAKING - `web-core-spring-boot-starter`: `PageResponse`'s totals are nullable** (`Long`,
  `Integer`) and omitted from the JSON when the caller declined the count. Its canonical constructor
  gained `offset` and changed the totals' types; the factory methods are the supported way in and are
  source-compatible apart from the new `of(List, long, int, Long)`.
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


### Removed

- **BREAKING - `odata-filter-spring-boot-starter`: `FilterAppliedEvent`'s `rawFilter` and
  `resolvedPredicate` components.** Both were the caller's literal values verbatim - `rawFilter` is the
  `$filter` string as sent, and `resolvedPredicate` was `predicate.toString()` - so a filter on an e-mail,
  phone or document-number field put that value into whatever listened. They are replaced by a
  `FilterSummary` carrying the property paths and the operators and no value in any form: not hashed, not
  truncated, not collected.

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
