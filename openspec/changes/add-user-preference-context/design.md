## Context

This change adds a module-crossing contract, so a design is required.

The facts it is built on were read out of the artifacts rather than remembered, because three of them
are counter-intuitive:

1. **`AcceptHeaderLocaleResolver` is not timezone-aware.** `javap` on
   `spring-webmvc:6.1.14` gives
   `public class AcceptHeaderLocaleResolver extends AbstractLocaleResolver` — a plain
   `LocaleResolver`, five methods, none of them `resolveLocaleContext`. The timezone-aware branch of
   the hierarchy is `AbstractLocaleContextResolver implements LocaleContextResolver`, which
   `AcceptHeaderLocaleResolver` does not extend. `DispatcherServlet` therefore publishes a
   `SimpleLocaleContext`, `LocaleContextHolder.getTimeZone()` finds no
   `TimeZoneAwareLocaleContext`, and returns `TimeZone.getDefault()`. Everything downstream — a
   `@JsonFormat` without an explicit zone, a `DateTimeFormat` conversion, `MessageSource` date
   arguments — reads the container's zone and nobody is told.
2. **`SettingsLookup` is already cached and already resolves the tenant.** `DefaultSettingsLookup`
   holds a `LudwigCache<SettingsSubject, ResolvedSettings>` from `cache-spring-boot-starter`, and the
   `PrincipalRef` overloads take the tenant from the security context. So a per-request stored lookup
   is a cache read, not a query, and this change must add **no cache of its own** — doing so would be
   a second cache primitive, which `RuleGroup.CACHING` fails the build on.
3. **`ResolvedSettings` reports the layer each value came from.** `ResolvedValue.layer()` and
   `SettingLayer.isMoreSpecificThan` are what make a correct precedence possible at all; see the
   decision below, which is the single most load-bearing detail in this change.

## Goals / Non-Goals

Goals: one answer to "what locale and zone is this caller in", reachable from a MapStruct mapper
without threading a parameter, correct on a worker thread, and sourced from the user's stored setting
when they have one.

Non-goals are in the proposal. The one worth repeating here, because it is the thing a later change
will be tempted to do: this contract must not grow a third dimension. Two dimensions that cannot be
derived from one another is a value object; five is a settings store, and there is one of those.

## Decisions

### The value is a `Locale` and a `ZoneId`, and everything else is derived

```java
public record UserPreferences(Locale locale, ZoneId zone) {
    public static UserPreferences current();                 // ambient, never null
    public static Optional<UserPreferences> currentIfResolved();
    public Scope bind();                                     // try-with-resources, for a worker thread
    public DateTimeFormatter dateTimeFormatter(FormatStyle style);
    public NumberFormat numberFormat();
    public DayOfWeek firstDayOfWeek();
    public OffsetDateTime at(Instant instant);
}
```

A number format, a decimal separator, a first day of week and a short date pattern are all functions
of the locale, and a function belongs on the record rather than beside it: a stored
`firstDayOfWeek` could disagree with the stored locale, and then two correct-looking reads of the
same user produce a calendar that starts on Monday with Sunday's column highlighted. A preference
that genuinely does **not** derive — a digest cadence, a landing page — is a `SettingDefinition` in
the service that cares, read through `SettingsLookup`, and not a field here.

### The ambient accessor is static, and that is the point

`UserPreferences.current()` is a static read of `LocaleContextHolder`, mirroring
`SecurityPrincipals.require()` and for the same reason its javadoc already gives: the same lookup has
to work from a MapStruct-generated mapper, a JPA listener and a repository fragment, none of which is
a good place to thread a service through. An injected `UserPreferenceResolver` bean would be the
cleaner shape and would not be reachable from the three call sites that need it most, which is how
`ZoneId.systemDefault()` gets called instead.

`current()` never returns null and never throws — unlike `SecurityPrincipals.require()`. The
asymmetry is deliberate: an unscoped *query* is a data leak, so `require()` is right to throw, while
an unresolved *presentation* preference has a correct conservative answer (the configured default)
and throwing would turn a cosmetic gap into a 500 on a path that was only formatting a date.

### Precedence is per dimension, and a stored value only counts if something was actually stored

Three shipped sources, each answering `Optional` **per dimension** so that a stored zone with no
stored locale still lets `Accept-Language` decide the locale:

| Order | Source | Answers from |
|---|---|---|
| 100 | `StoredUserPreferenceSource` (in `user-settings`) | `WellKnownSettings.LOCALE` / `TIMEZONE`, **only** when the resolved layer is more specific than `PLATFORM` |
| 200 | `RequestHeaderPreferenceSource` (in `web-core`) | `Accept-Language`, and the configured timezone header |
| `LOWEST_PRECEDENCE` | `ConfiguredPreferenceSource` (in `web-core`) | `ludwig.web.i18n.default-locale`, `ludwig.web.preferences.default-zone` |

Two decisions inside that table:

**A stored choice beats a request header, not the other way round.** `Accept-Language` is a hint the
*browser* sends; a stored locale is a choice the *user* made, usually precisely because the browser
was sending the wrong one. A platform where setting your language in your profile is silently
overridden by your browser's configuration has a settings screen that does not work.

**`PLATFORM` and `DEFAULT` layers abstain.** This is the load-bearing detail. `SettingsLookup.getAll`
always answers for a declared definition — a user who has never touched their timezone resolves to
`SettingLayer.DEFAULT` with the definition's `UTC`. A source that answered from that would make every
caller UTC and every `Accept-Language` header ignored, with the chain below it unreachable. The
`PLATFORM` layer abstains for the same reason one step up: it is supplied by deployment
configuration, `ConfiguredPreferenceSource` already *is* the deployment's answer, and a
deployment-wide locale overriding every caller's `Accept-Language` is the half-translated-response
failure the `i18n-bundles` capability already forbids. So the stored source answers for `USER`,
`ROLE` and `TENANT` — `layer.isMoreSpecificThan(SettingLayer.PLATFORM)` — and abstains otherwise.

A deployment that disagrees reorders the beans: the sources are ordered `@Bean`s, not a hard-coded
list, and `UserPreferenceSource` publishes the three order constants so a replacement can be placed
relative to them rather than at a magic number.

### Resolution replaces the `LocaleResolver`, and is lazy

`UserPreferenceLocaleContextResolver implements LocaleContextResolver` is registered as
`localeResolver`, taking the place of the `AcceptHeaderLocaleResolver`
`WebCoreLocalizationAutoConfiguration` registers today. That bean name is the mechanism:
`DispatcherServlet` looks it up by name, sees a `LocaleContextResolver`, and publishes whatever
`LocaleContext` it returns — so `LocaleContextHolder.getLocale()` **and** `getTimeZone()` become
correct for every existing caller with no code change anywhere. Shipping a filter beside the existing
resolver instead would leave two answers to "what locale is this request", differing exactly when a
stored preference exists, which is the case that matters.

`resolveLocaleContext` returns a **lazy, memoising** `TimeZoneAwareLocaleContext` rather than a
resolved one. A dispatch that never formats anything — an actuator probe, a 204, a byte-range
download — then performs no settings read at all, and a dispatch that formats twenty fields performs
one. Laziness is safe in the degenerate direction: if some Spring internal calls `getLocale()`
eagerly, the result is the eager behaviour, not a wrong answer.

`setLocaleContext` throws `UnsupportedOperationException`, as `AcceptHeaderLocaleResolver.setLocale`
does, with a message naming the write path — a preference is changed by writing the setting, not by
mutating a request's context, and a `LocaleChangeInterceptor` silently doing the latter would produce
a preference that lasts one request and then appears to have been lost.

The resolver backs off entirely on `ludwig.web.preferences.enabled=false`, in which case the previous
`AcceptHeaderLocaleResolver` is registered exactly as before. That keeps this change revertible by
configuration, which for a behaviour that reaches every response in the platform is worth one
property.

### The mapper path is MapStruct's own, and carries no MapStruct dependency

`UserPreferenceFormatter` is an ordinary Spring bean that a mapper names:

```java
@Mapper(componentModel = "spring", uses = UserPreferenceFormatter.class)
public interface ProductMapper {
    ProductResponse toResponse(Product product);   // Instant createdAt -> OffsetDateTime createdAt
}
```

MapStruct resolves an unmapped `Instant -> OffsetDateTime` through a single-argument method on a
`uses` type automatically, so no mapping method signature changes and no `@Mapping` annotation is
needed. The deliberate constraint that makes this work is **exactly one method per source/target
pair** on the formatter. MapStruct reports an ambiguity as a compile error naming both candidates and
requires `qualifiedByName` to break it, which would mean annotating the formatter with
`org.mapstruct.Named` — and `web-core` must not take a MapStruct dependency to publish a type that is
useful without it. One method per pair makes selection unambiguous by construction instead; a service
that needs a second rendering of the same pair writes its own `@Named` wrapper, which is where that
annotation belongs.

A note at the point of the rule says so, because this is a constraint no check can see: ArchUnit
cannot know that two methods on a class are MapStruct-ambiguous, and the failure surfaces as a
compile error in the *consuming* module with a clear message, which is the acceptable half of this
trade.

### `export` keeps its seam and loses its literal

`ReportCaller` gains `UserPreferences preferences()`; `locale()` and `zone()` become `default`
methods over it. A service that implements `ReportCaller` itself is unaffected unless it wants the new
method, and `SecurityReportCaller.zone()` stops being `ZoneId.of("UTC")`. The javadoc comment there
is kept and corrected: UTC was chosen over the server's zone, which was the right half of the
decision; the other half is now available.

This is a source-compatible addition to a published interface (a new method with existing methods
redefined as defaults over it). It is not binary-compatible for an *implementor* compiled against the
old interface only in the sense that it must now supply `preferences()`; the revapi gate landed inert
by the `api-evolution` capability, and the difference is recorded in the changelog rather than hidden.

### Dependency-direction check

*Does `web-core-spring-boot-starter` already depend on `user-settings-spring-boot-starter`, directly
or transitively?* `project-index.json` gives `web-core-spring-boot-starter` **zero** in-repo
dependencies, and lists `user-settings-spring-boot-starter` among its 14 `inRepoDependents`. The edge
this change relies on — `user-settings` → `web-core` — therefore already exists and is the direction
being used: the SPI is declared in `web-core` and implemented in `user-settings`. **No new edge is
added, and no cycle is possible.**

*Does `web-core` acquire a persistence dependency?* No. `UserPreferenceSource` names `Locale`,
`ZoneId` and `Optional`. This is the same construction the `long-running-operations` capability
requires for the same reason, and it is why the stored implementation cannot live in `web-core`.

*Does `export-spring-boot-starter` already depend on `web-core`?* Yes, `compile`, so
`ReportCaller.preferences()` adds no edge either.

*Does `user-settings` already depend on `security-spring-boot-starter`?* Yes — its existing
`UserSettingsPreferenceSource` and `SecurityContextTenantResolver` import
`ru.ludwigandreas.security.authz.PrincipalRef`. The stored source needs `SecurityPrincipals` and adds
no edge.

### POM changes

**None of the three POMs change.** `pom.xml` (root) is untouched: no new plugin, no new build
configuration. `ludwig-bom` is untouched: this change adds no third-party dependency — every type it
names is in `java.base`, `spring-core`, `spring-context` or `spring-webmvc`, all already declared.
`ludwig-service-parent` is untouched: nothing here is a service build decision.

The only POM edit is `web-core-spring-boot-starter/pom.xml` gaining nothing — `spring-webmvc` is
already declared `<optional>true</optional>`, which is the dependency the `LocaleContextResolver`
needs, and the new configuration class is nested behind `@ConditionalOnWebApplication` exactly as
`LocaleResolverConfiguration` already is. `scripts/manifest.sh build` is therefore not required, and
the task list says so rather than running it for nothing.

### The mechanical checks

Both are ArchUnit, in a new `RuleGroup.PRESENTATION`, because both are facts about structure and
dependencies that are visible in bytecode. Neither is a source-text fact, so neither belongs to
Checkstyle — the `enforcement-triad` capability forbids the overlap.

| Rule | Fails when | Why ArchUnit and not Checkstyle |
|---|---|---|
| `noAmbientDefaultLocaleOrZone` | any class calls `Locale.getDefault()`, `TimeZone.getDefault()`, `ZoneId.systemDefault()` or `Clock.systemDefaultZone()` | a method call is a bytecode fact; a text rule would also match the words in a comment or a javadoc link, of which this change writes several |
| `noSecondCallerPreferenceType` | a class's whole instance state is exactly a `java.util.Locale` and a `java.time.ZoneId` | field types are bytecode facts |

The second rule needs the same care `RuleGroup.OPERATIONS` needed, and its javadoc states the
distinction, because the distinction *is* the rule: a type that exists **in order to be** the pair is
a restatement of this contract; a type that carries the pair **alongside its own state** is a domain
object that needs a locale and a zone, which is every legitimate holder of them. So the discriminator
is "the declared non-static instance fields are exactly a `Locale` and a `ZoneId` and nothing else".

That narrowness was arrived at by measurement, and the first attempt is worth recording because it is
the mistake this rule class invites. A version asking only that both fields be *present*, with an
exemption for a field named like a subject, was run against the whole reactor and reported two
violations — both legitimate code. `notification-service`'s `RecipientPreferences` and
`StoredPreferences` carry the pair beside a `QuietHours`, a `DigestMode` and an `OptOutMatrix`, are
built per recipient on a queue worker where there is no ambient caller at all, and have no field named
like a subject, so the exemption never fired. The same rule would also have flagged `export`'s
`ReportRequest`, a fifteen-field request DTO. A rule that has to be suppressed inside the platform
that introduced it does not survive a quarter.

The accepted limit, written at the rule: **a third field evades it.** That is the same trade
`CachingRules` makes with its name heuristic — the rule exists to catch the reasonable local decision,
not the determined evasion, and a rule broad enough to catch the second would flag every domain object
in the platform. The subject-field-name list survives as a second clause so the intent is legible to
whoever widens this later.

`export`'s `RenderContext(Locale, ZoneId)` was the clearest restatement in the repository and is
consolidated rather than exempted: it becomes `record RenderContext(UserPreferences)`, keeping
`locale()` and `zone()` as accessors so the renderer SPI's vocabulary is unchanged and one construction
site moves instead of twenty-one call sites. It is not reached by the rule either way — `export` runs
its own `ExportArchitectureTest` rather than the shared `@AnalyzeArchitecture` suite — and that is
precisely why it had to be done rather than left: a design claiming one mechanism while its clearest
duplicate survives behind a module that does not run the check is a claim the next reader cannot trust.

`noAmbientDefaultLocaleOrZone` needs two named exemptions, both of which must be in the rule rather
than in prose: `ConfiguredPreferenceSource` does not call them (it reads configuration), but
`UserPreferences.current()`'s fallback and the test fixtures that assert the fallback do, and a
`Clock.systemDefaultZone()` in a scheduler that is measuring elapsed time rather than presenting one
is legitimate. The rule is scoped to classes that are not in `ru.ludwigandreas.webcore.preference`
and the exemption list is a constant in the rule class with a comment per entry.

### What cannot be checked

Recorded here because this repository treats the unmechanised list as the backlog rather than as an
embarrassment:

- **That a mapper actually uses the formatter.** A mapper that converts `Instant -> OffsetDateTime`
  with `instant.atOffset(ZoneOffset.UTC)` is not calling a forbidden method and is still wrong.
  ArchUnit could forbid `ZoneOffset.UTC` outright, and must not: it is correct in a persistence
  mapping, in a test fixture and in an audit record, which are the majority of its uses. This stays a
  review question and is written into the capability as a scenario so the review has something to
  point at.
- **That a service declared `WellKnownSettings.LOCALE` and `TIMEZONE` when it wanted them honoured.**
  The stored source backs off silently when the definitions are not registered, which is correct —
  `WellKnownSettings`' opt-in promise is that a service resolves exactly what it declares — and means
  a service that wanted stored preferences and forgot the `SettingDefinitionSource` bean gets
  header-and-configuration resolution with no warning. Mitigated, not solved: the resolver logs the
  decision once at startup at `INFO`, naming which sources are active, so the gap is visible in a log
  rather than only in a surprised user.
- **Whether the configured `default-zone` is the right zone for the deployment.** An operator setting
  `UTC` because it was in the example is indistinguishable from an operator setting `UTC` because the
  business runs on UTC.
