## ADDED Requirements

### Requirement: One caller-preference contract, owned by `web-core`
`ru.ludwigandreas.webcore.preference` SHALL own the caller's presentation preferences: the value
type (`UserPreferences`), the resolution SPI (`UserPreferenceSource`), the ambient accessor, the
off-thread binding scope and the mapper-facing conversions. No other module SHALL declare a type
holding a locale and a zone for the current caller.

#### Scenario: A module needs the caller's locale and zone
- **WHEN** a module needs to present an instant or a number to the caller
- **THEN** it reads `UserPreferences.current()`, or injects `UserPreferenceFormatter`, rather than
  declaring a record of its own

#### Scenario: A module declares a second caller-preference type
- **WHEN** a class outside `ru.ludwigandreas.webcore.preference` declares, as its whole instance
  state, exactly a `java.util.Locale` and a `java.time.ZoneId`
- **THEN** `RuleGroup.PRESENTATION`'s `noSecondCallerPreferenceType` rule fails the build. A type
  that exists *in order to be* the pair is a restatement of the contract, and a second type means a
  second precedence order that will differ exactly where it matters

#### Scenario: A module holds a locale and a zone alongside its own state
- **WHEN** a class carries the pair together with other fields — a notification recipient's quiet
  hours, digest cadence and opt-out matrix; a report request's parameters, columns and formats
- **THEN** the rule does not fire. Requiring "and nothing else" is what makes the rule correct rather
  than merely strict: a type carrying the pair plus its own state is a domain object that needs a
  locale and a zone, which is every legitimate holder of them. The narrowness was measured, not
  chosen — a first version asking only that both fields be present reported
  `notification-service`'s `RecipientPreferences` and `StoredPreferences` as violations

#### Scenario: A type evades the rule by declaring a third field
- **WHEN** a restatement adds an unused third field
- **THEN** the rule does not fire, and this is an accepted limit written at the rule rather than a
  defect. The rule exists to catch the reasonable local decision, not the determined evasion, and a
  rule broad enough for the second would flag every domain object in the platform and be switched
  off within a quarter

#### Scenario: A module holds a locale and a zone for a named subject
- **WHEN** a class holds a locale and a zone for a *subject other than the caller* — a notification
  recipient resolved on a queue worker, where there is no caller at all — alongside that subject's
  other per-subject state
- **THEN** that is not a restatement of this contract and the rule does not fire. The two are
  different things: this contract is ambient and request-scoped, a subject's preferences are looked
  up per subject through `SettingsLookup`, and an ambient static accessor on a worker thread would
  resolve the wrong person's preferences or nobody's

### Requirement: `UserPreferences` holds the two dimensions that do not derive, and derives the rest
`UserPreferences` SHALL hold exactly a `Locale` and a `ZoneId`, both non-null. Anything that is a
function of those SHALL be exposed as a method on it rather than stored beside it. A preference that
is not a function of either SHALL be a `SettingDefinition` in the module that needs it.

#### Scenario: A number has to be rendered in the caller's locale
- **WHEN** a mapper needs a decimal separator, a first day of week or a short date pattern
- **THEN** it calls `numberFormat()`, `firstDayOfWeek()` or `dateTimeFormatter(FormatStyle)` on the
  resolved preferences, rather than reading a separately stored setting that could disagree with the
  stored locale

#### Scenario: A third preference is proposed as a field
- **WHEN** a change proposes adding a digest cadence, a default currency or a landing page to
  `UserPreferences`
- **THEN** that is a defect against this requirement: it belongs in
  `user-settings-spring-boot-starter` as a `SettingDefinition`, and a second settings store in
  `web-core` would also require the persistence dependency `web-core` must not have

### Requirement: The ambient accessor always answers and never throws
`UserPreferences.current()` SHALL return a fully populated value on any thread, falling back to the
configured defaults when nothing has been resolved or bound. It SHALL NOT return null and SHALL NOT
throw. `currentIfResolved()` SHALL return empty when nothing has been resolved, for code that needs
to distinguish the two.

#### Scenario: A mapper runs outside a request
- **WHEN** a mapper is invoked from a scheduled job, a Kafka listener or a unit test, with no request
  and no bound scope
- **THEN** `current()` returns the configured default locale and zone, so a formatting call on a
  background path produces a defensible value rather than a `NullPointerException` on a path that was
  only rendering a date

#### Scenario: Code must know whether a real caller was resolved
- **WHEN** an audit enricher wants to record the caller's zone only when there was a caller
- **THEN** `currentIfResolved()` returns empty rather than the configured default, so the record does
  not claim a preference nobody expressed

### Requirement: `LocaleContextHolder` carries the resolved zone, not the container's
In a servlet application, the registered `localeResolver` SHALL be a `LocaleContextResolver` that
publishes a `TimeZoneAwareLocaleContext` built from the resolved preferences.

#### Scenario: Any existing caller of `LocaleContextHolder.getTimeZone()`
- **WHEN** code anywhere in the platform reads `LocaleContextHolder.getTimeZone()` on a request
  thread
- **THEN** it receives the caller's resolved zone. Before this change it received
  `TimeZone.getDefault()` — the container's zone — because `AcceptHeaderLocaleResolver` is a plain
  `LocaleResolver` and never publishes a timezone-aware context

#### Scenario: A request that formats nothing
- **WHEN** a dispatch completes without any code asking for the locale or the zone
- **THEN** no preference source is consulted and no settings read is performed, because the published
  `LocaleContext` resolves lazily and memoises

#### Scenario: Something tries to change the preference through the request context
- **WHEN** `setLocaleContext` is called on the resolver — by a `LocaleChangeInterceptor`, for instance
- **THEN** it throws `UnsupportedOperationException` naming the write path. A preference is changed by
  writing the setting; mutating a request's context would produce a change that lasts one request and
  then looks to the user as though it was lost

#### Scenario: The contract has to be switched off
- **WHEN** `ludwig.web.preferences.enabled=false`
- **THEN** the `AcceptHeaderLocaleResolver` is registered exactly as before and nothing else in the
  starter changes behaviour, so a deployment can revert a change that reaches every response without
  reverting a build

### Requirement: Preferences resolve per dimension, from ordered sources
A `UserPreferenceSource` SHALL answer `Optional` **per dimension**. Resolution SHALL take each
dimension from the first source in order that answers for it, independently of the other.

#### Scenario: A user stored a zone but not a locale
- **WHEN** the stored source answers a zone and abstains on the locale, and the request carries
  `Accept-Language: ru`
- **THEN** the caller is resolved as `ru` in the stored zone — not as the default locale, which a
  whole-value precedence would have produced

#### Scenario: A deployment needs a different precedence
- **WHEN** a deployment wants its own source to outrank the stored one
- **THEN** it declares an ordered bean relative to the constants `UserPreferenceSource` publishes,
  rather than editing a hard-coded list

### Requirement: A stored choice outranks a request header, which outranks configuration
The shipped order SHALL be: the stored preference, then the request headers
(`Accept-Language` and the configured timezone header), then configuration.

#### Scenario: A user's stored language differs from their browser's
- **WHEN** a user has stored `ru` and their browser sends `Accept-Language: en`
- **THEN** the response is in Russian. `Accept-Language` is a hint the browser sends; a stored locale
  is a choice the user made, usually because the browser was sending the wrong one, and a platform
  where the browser silently wins has a settings screen that does not work

#### Scenario: No preference anywhere
- **WHEN** nothing is stored and the request carries no usable header
- **THEN** the caller resolves to `ludwig.web.i18n.default-locale` and
  `ludwig.web.preferences.default-zone`, never to `Locale.getDefault()` or `ZoneId.systemDefault()`

### Requirement: Only an actually stored value counts as stored
The stored source SHALL answer for a dimension only when the resolved value's `SettingLayer` is more
specific than `PLATFORM` — that is, `USER`, `ROLE` or `TENANT`. It SHALL abstain on `PLATFORM` and
`DEFAULT`.

#### Scenario: A user has never opened the settings screen
- **WHEN** `SettingsLookup.getAll` answers `user.timezone` from `SettingLayer.DEFAULT`, which is what
  it does for every declared definition nobody has set
- **THEN** the stored source abstains and the request header is consulted. A source that answered
  from the `DEFAULT` layer would make every caller UTC, ignore every `Accept-Language` header, and
  make the two sources below it unreachable

#### Scenario: A deployment-wide value is configured at the platform layer
- **WHEN** the resolved layer is `PLATFORM`, which is supplied by deployment configuration
- **THEN** the stored source abstains, because `ConfiguredPreferenceSource` already is the
  deployment's answer and sits below the headers — a deployment-wide locale outranking every caller's
  `Accept-Language` is the half-translated response the `i18n-bundles` capability forbids

#### Scenario: The service never declared the well-known definitions
- **WHEN** a service has not contributed `WellKnownSettings.LOCALE` and `TIMEZONE` as a
  `SettingDefinitionSource`
- **THEN** the stored source abstains for each dimension that is not declared, preserving
  `WellKnownSettings`' promise that a service resolves exactly the settings it declares, and names
  which dimensions it can answer in the startup line. The abstention is decided by asking the
  `SettingDefinitionRegistry`, not by catching `UnknownSettingException`, and the source is
  registered rather than conditionally absent: a condition cannot inspect a registry that has not
  been built yet, and a silently absent bean is indistinguishable from a deliberate choice, which
  leaves "my saved timezone does nothing" with no visible cause

#### Scenario: The settings read fails
- **WHEN** reading settings throws — a replica mid-migration, a database blip
- **THEN** the source abstains and logs, and the request is answered from the header or
  configuration. The failure direction matters: abstaining renders a date in the wrong zone and is
  recovered by the next read, propagating turns a formatting concern into a 500

### Requirement: The timezone header is configuration, not a standard
There is no standardised request header carrying a timezone. The header name SHALL be configurable
(`ludwig.web.preferences.time-zone-header`), its default documented as a convention rather than a
standard, and an unparseable or unknown value SHALL be ignored rather than rejected.

#### Scenario: A client sends an invalid zone
- **WHEN** the header carries `Mars/Olympus_Mons` or an empty string
- **THEN** the source abstains and the chain continues. A 400 here would fail a request that was
  otherwise valid, over a presentation hint the client did not have to send

### Requirement: Preferences are bindable onto a thread that has no request
`UserPreferences` SHALL expose a scope that binds it to the current thread and restores the previous
context on close.

#### Scenario: An asynchronous report run
- **WHEN** a report is accepted on a request thread and executed on a pooled thread
- **THEN** the request thread captures `UserPreferences.current()` into the run, and the worker binds
  it for the duration. Without this the run would render in the container's zone, which is the bug
  class that is invisible in development because the two coincide there

#### Scenario: A scope is closed
- **WHEN** the scope is closed
- **THEN** the thread's previous `LocaleContext` is restored, including when it was absent, so a
  pooled thread does not carry one caller's preferences into the next task

### Requirement: A mapper resolves preferences through the formatter, with no signature change
`UserPreferenceFormatter` SHALL be a bean usable as a MapStruct `uses` type, carrying **exactly one
method per source/target type pair**.

#### Scenario: A DTO exposes an instant
- **WHEN** a mapper is declared `@Mapper(componentModel = "spring", uses = UserPreferenceFormatter.class)`
  and a `service.model` `Instant` maps to a `web.dto` `OffsetDateTime`
- **THEN** MapStruct selects the formatter's conversion automatically; no mapping method signature
  changes and no `@Mapping` annotation is added

#### Scenario: A second method for the same pair is added to the formatter
- **WHEN** a second single-argument `Instant -> String` method is added
- **THEN** every consuming module's MapStruct build fails with an ambiguity naming both candidates.
  This is why the one-method-per-pair rule exists and why it is a comment at the point of the rule
  rather than a check: ArchUnit cannot know that two methods are MapStruct-ambiguous

#### Scenario: A service needs a second rendering of the same pair
- **WHEN** a service wants both an ISO instant and a localized display string in one DTO
- **THEN** it writes its own `@Named` wrapper in its own mapper, which is where that annotation
  belongs — `web-core` publishes a type that is useful without MapStruct and takes no dependency on it

### Requirement: Nothing presents a time or a number from a JVM default
No class SHALL call `Locale.getDefault()`, `TimeZone.getDefault()`, `ZoneId.systemDefault()` or
`Clock.systemDefaultZone()` outside `ru.ludwigandreas.webcore.preference` and the exemptions named in
the rule.

#### Scenario: A module reads the system zone to render a timestamp
- **WHEN** any class calls `ZoneId.systemDefault()`
- **THEN** `RuleGroup.PRESENTATION`'s `noAmbientDefaultLocaleOrZone` rule fails the build. The
  container's zone is UTC in the datacentre and the developer's zone on a laptop, so the defect is
  invisible exactly where it would be caught

#### Scenario: A module renders an instant in a fixed zone instead
- **WHEN** a mapper writes `instant.atOffset(ZoneOffset.UTC)` rather than using the resolved
  preferences
- **THEN** no rule fires and the output is still wrong. This is deliberately unchecked:
  `ZoneOffset.UTC` is correct in a persistence mapping, a test fixture and an audit record, which are
  the majority of its uses, so forbidding it would be a rule that is wrong more often than right. It
  is a review question, recorded here so the review has something to point at

### Requirement: `export` renders in the caller's zone
`ReportCaller` SHALL expose the resolved preferences, and the shipped implementation SHALL NOT return
a literal zone.

#### Scenario: A report is run by a user outside UTC
- **WHEN** a user whose resolved zone is `Asia/Yekaterinburg` runs a report and names no zone in the
  request body
- **THEN** every instant in the produced file is rendered in `Asia/Yekaterinburg`. Before this change
  it was rendered in UTC — five hours out on every row — because `SecurityReportCaller.zone()` was a
  hard-coded `ZoneId.of("UTC")` with nowhere to ask

#### Scenario: The request names a zone explicitly
- **WHEN** `RunReportRequest.timeZone` is set
- **THEN** it still wins over the resolved preference, because a report request naming a zone is an
  explicit instruction about one file and not a change of preference
