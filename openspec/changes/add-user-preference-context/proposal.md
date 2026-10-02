## Why

`web-core-spring-boot-starter` resolves **half** of a caller's presentation context and none of it in
a form anything can reuse. `WebCoreLocalizationAutoConfiguration` registers an
`AcceptHeaderLocaleResolver`, so `LocaleContextHolder.getLocale()` is correct on a request thread —
and `LocaleContextHolder.getTimeZone()` is not, because `AcceptHeaderLocaleResolver` is a plain
`LocaleResolver` and never produces a `TimeZoneAwareLocaleContext`. Spring's answer to
"what zone is this request in" therefore falls through to `TimeZone.getDefault()`, which is the
container's zone, which is UTC in the datacentre and the developer's zone on a laptop. That is the
class of bug that is invisible in development and an hour out in production.

The gap is already being paid for twice, in the two modules that needed the answer:

- `export-spring-boot-starter`'s `SecurityReportCaller.zone()` is a **hard-coded
  `ZoneId.of("UTC")`** with a comment explaining that the server's zone would be wrong. It is right
  not to use the server's zone and it is still not the caller's zone: a report whose timestamps are
  rendered in UTC for a user in Yekaterinburg is five hours wrong on every row, and the module has
  nowhere to ask. Its `RenderContext(Locale, ZoneId)` record is the pair, written once.
- `notification-service`'s `RecipientPreferences(Locale, ZoneId, ...)` is the pair, written again.

And `user-settings-spring-boot-starter` already *stores* both — `WellKnownSettings.LOCALE` and
`WellKnownSettings.TIMEZONE` are declared, documented, user-editable, cache-backed and read by
exactly one consumer (notification's `UserSettingsPreferenceSource`), because there is no ambient
path from a stored setting to the thread rendering a response. A user can set their timezone in
their profile today and every API response still renders in the container's zone.

The concrete consequence for the shape this repository asks services to follow: a MapStruct mapper
turning a `service.model` into a `web.dto` has **no standard way** to render an `Instant` in the
caller's zone or a `BigDecimal` in the caller's locale. The options available to it today are to
thread a parameter through every mapping method in the service, or to call
`ZoneId.systemDefault()` and be wrong. Both have happened; neither is checkable, because there is no
right answer to check against.

## What Changes

- **One caller-preference contract in `web-core`**, `ru.ludwigandreas.webcore.preference`, owning the
  value (`UserPreferences`: a `Locale` and a `ZoneId`, and nothing that derives from them), the
  resolution SPI (`UserPreferenceSource`), the ambient accessor (`UserPreferences.current()`), the
  off-thread rebinding scope (`UserPreferences.bind()`), and the mapper-facing conversions
  (`UserPreferenceFormatter`).
- **`LocaleContextHolder` carries the zone too.** `UserPreferenceLocaleContextResolver` replaces the
  `AcceptHeaderLocaleResolver` and publishes a `TimeZoneAwareLocaleContext` resolved from the source
  chain, so `MessageSource`, Bean Validation, Jackson and any existing `LocaleContextHolder` caller
  pick the caller's zone up with no code change at all. It resolves **lazily and once per request**,
  so a request that never asks never pays for a settings read.
- **Three shipped sources, ordered, per dimension.** A stored preference (an explicit choice the user
  made) beats a request header (a hint the client sent on their behalf) beats configuration. Each
  source answers `Optional` per dimension, so a stored zone with no stored locale still lets
  `Accept-Language` decide the locale.
- **A standard mapper path.** `UserPreferenceFormatter` is a bean a mapper names in
  `@Mapper(uses = ...)`, which is MapStruct's own mechanism: `Instant -> OffsetDateTime` and
  `Instant -> LocalDate` are then selected automatically with no change to any mapping signature.
  It carries exactly one method per source/target pair so that selection cannot be ambiguous.
- **`user-settings` ships the stored source.** `StoredUserPreferenceSource` reads
  `WellKnownSettings.LOCALE` and `TIMEZONE` through `SettingsLookup` for the current principal and
  tenant, abstains when either is absent, and degrades to abstention on failure rather than failing
  the request. It is registered only when the service has actually declared those definitions, which
  keeps `WellKnownSettings`' opt-in promise intact. The dependency direction is already correct:
  `user-settings` depends on `web-core`, not the reverse.
- **`export` stops hard-coding UTC.** `SecurityReportCaller.zone()` returns the caller's resolved
  zone; `ReportCaller` gains `preferences()` and the two single-dimension accessors become default
  methods over it, so the seam is unchanged for a service that implements it. `RenderContext` is
  built from the resolved pair instead of from a literal.
- **Two mechanical checks**, both ArchUnit, both in a new `RuleGroup.PRESENTATION`:
  `noAmbientDefaultLocaleOrZone` fails a build that calls `Locale.getDefault()`,
  `ZoneId.systemDefault()`, `TimeZone.getDefault()` or `Clock.systemDefaultZone()` on a presentation
  path, and `noSecondCallerPreferenceType` fails a class that restates the locale-and-zone pair.
  The second rule's javadoc states what separates a *caller's* ambient preferences (this contract)
  from a *subject's* stored preferences resolved for somebody else (`SettingsLookup`), because that
  distinction is the whole rule.

## Non-goals

- **Not a second preference store.** `user-settings-spring-boot-starter` owns storage, layering,
  validation, caching, audit and the `/me/settings` API. This change adds no table, no entity and no
  write path, and `web-core` acquires no persistence dependency — the same constraint the
  long-running-operation contract already carries.
- **Not a generic settings bus.** `UserPreferences` holds the two dimensions that every presentation
  path needs and that cannot be derived from each other. A third preference — a digest cadence, a
  default currency, a landing page — is a `SettingDefinition` the service declares and reads itself,
  not a field here. Anything that *does* derive from the locale (number format, first day of week,
  decimal separator) is derived on `UserPreferences` rather than stored beside it.
- **Not notification's `RecipientPreferences`.** Those are a *recipient's* preferences, resolved per
  subject on a queue worker where there is no caller, for a fan-out to a third party. This contract is
  the *caller's* ambient context on a request thread. Collapsing them would put a static ambient
  accessor on a code path that has no ambient anything, and the ArchUnit restatement rule is written
  so that it does not fire on the per-subject shape. Stated here because the superficial similarity
  (a `Locale` beside a `ZoneId`) is exactly what makes it a tempting and wrong consolidation.
- **No change to how a locale reaches a message bundle.** The `MessageSource`, the supported-locale
  list and the validator wiring are untouched; they read `LocaleContextHolder`, which now simply
  carries a better answer.
- **No new HTTP standard.** There is no standardised request header for a timezone, so the header
  name is configuration with a documented default rather than a constant presented as a standard.

## Affected modules

Names and tiers are from `project-index.json` / `scripts/manifest.sh module <path>`.

| Module | Tier | Change |
|---|---|---|
| `web-core-spring-boot-starter` | library (parent `common`, imports `ludwig-bom`) | the new `preference` package, `WebCoreProperties.Preferences`, a new autoconfiguration, the `LocaleContextResolver` |
| `user-settings-spring-boot-starter` | library (parent `common`, imports `ludwig-bom`) | `StoredUserPreferenceSource` + its conditional registration |
| `export-spring-boot-starter` | library (parent `common`, imports `ludwig-bom`) | `ReportCaller.preferences()`, `SecurityReportCaller` stops returning a literal UTC |
| `architecture-rules` | library (parent `common`, imports `ludwig-bom`) | `RuleGroup.PRESENTATION` and its two rules |

### In-repo dependents the gate must run

- `web-core-spring-boot-starter` — **14**: `audit-spring-boot-starter`, `crud-service-example`,
  `db-core`, `export-spring-boot-starter`, `file-ingest-spring-boot-starter`,
  `idempotency-spring-boot-starter`, `messaging-spring-boot-starter`, `notification-service`,
  `object-storage-spring-boot-starter`, `observability-spring-boot-starter`,
  `odata-filter-spring-boot-starter`, `rest-client-spring-boot-starter`,
  `security-spring-boot-starter`, `user-settings-spring-boot-starter`.
- `user-settings-spring-boot-starter` — `notification-service`.
- `export-spring-boot-starter` — none.
- `architecture-rules` — every module that enables it in test scope.

Because the fan-out from `web-core` is the whole platform, the gate for this change is
`mvn clean install` rather than a set of `-pl` builds, and `scripts/gate.sh` is asked for the list
rather than trusted from memory.

## Shared contracts touched

- **New capability** `user-preference-context`.
- **`i18n-bundles`** — modified. The capability currently describes locale resolution as
  `Accept-Language` only; it becomes the lowest-but-one rung of a documented chain, and the
  requirement that an unsupported locale yields the default in full has to hold for a *stored* locale
  too.
- **`module-dependency-direction`** — not modified, but the design states the check: no new edge is
  added to the reactor DAG in the direction that would create a cycle.
- **`long-running-operations`** — not modified. Named because it carries the identical constraint
  ("`web-core` must stay free of any persistence dependency") that this change must also satisfy, and
  satisfies the same way: an SPI in `web-core`, the implementation in the module that owns the data.
- **`enforcement-triad`** — not modified. Both new checks are ArchUnit, which owns structure and
  dependencies; neither is a source-text fact and so neither belongs to Checkstyle.
