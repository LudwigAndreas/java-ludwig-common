## Context

See `proposal.md` — *Why*. The constraints that shape the approach, all of them already in the tree:

- **`ServiceIdentityResolver`** resolves version from `Package.getImplementationVersion()` with a dead
  fallback to a `build.version` property. It has no notion of a commit.
- **`JsonLoggingInitializer`** runs at `ApplicationEnvironmentPreparedEvent`, ordered one step after
  `LoggingApplicationListener.DEFAULT_ORDER`, and **swaps encoders** on the appenders it finds rather
  than owning an appender. It replaces only `LayoutWrappingEncoder` instances, recursing through
  `AppenderAttachable` to reach appenders nested inside an `AsyncAppender`.
- **`ObservabilityEnvironmentPostProcessor`** publishes defaults as a `MapPropertySource` added with
  `addLast`, i.e. lowest precedence. It declares no order, which puts it last — after config data has
  been processed, so profiles are known.
- **`LogFieldNames`** is a 19-component record with three factories (`ecs()`, `otel()`, `flat()`).
- **revapi** runs reactor-wide with `revapi.semver.ignore`, so a breaking API difference needs a version
  bump or an explicit `revapi.differences` entry.
- **jib** keeps `creationTime` and `filesModificationTime` at the epoch so that building one commit twice
  yields an identical image digest.
- `git-commit-id-maven-plugin` is configured in `ludwig-service-parent` with
  `failOnNoGitDirectory=false`; `spring-boot-maven-plugin` is configured there but its `build-info` goal
  is not bound.

## Goals / Non-Goals

**Goals (design-level):**

- Add build provenance without touching `ServiceIdentity`, so the change is additive under revapi.
- Keep the encoder-swap architecture intact. The format decision moves, the installation mechanism does
  not.
- Make the human-readable path carry the same metadata as the structured path, from one declaration
  rather than two.
- Keep `observability-spring-boot-starter`'s in-repo dependency set unchanged.

**Non-Goals (design-level):**

- Replacing the encoder-swap with an owned appender. It would be cleaner in isolation and would break
  the existing deference to a service-authored `logback-spring.xml`.
- Making the MDC the transport for build identity. It is per-thread and the identity is per-process; the
  encoder reads it from the resolved identity directly, as it already does for `ServiceIdentity`.
- Any change to the sampler, the OTLP exporter, the RED metrics or the correlation id's propagation.

## Decisions

### D1 — A separate `BuildIdentity` record, not a widened `ServiceIdentity`

`ru.ludwigandreas.observability.core.BuildIdentity` — a record of `commitId`, `abbreviatedCommitId`,
`branch`, `buildTimestamp`, `ciBuildNumber`, `dirty`, each nullable, normalised blank-to-null in the
compact constructor exactly as `ServiceIdentity` does.

*Why not widen `ServiceIdentity`:* three reasons, each sufficient.

1. Adding a record component changes the canonical constructor's signature — a breaking difference under
   revapi, requiring a version bump or a `revapi.differences` entry for a purely additive feature.
2. `ServiceIdentity.toCommonMetricTags()` feeds Micrometer common tags. Every component is a candidate
   tag, and `branch` and `dirty` as tags multiply series by values that move independently of a release.
   Keeping the records separate means the cardinality question cannot be answered wrongly by accident.
3. The two have genuinely different purposes, which is the platform's own test for whether
   consolidation is right. `ServiceIdentity` is *the dimensions telemetry backends group by* — its
   javadoc says so. `BuildIdentity` is *provenance*: it is never grouped by, only read off one line.
   This is the same distinction `web-core` draws between a caller's ambient `UserPreferences` and a
   subject's stored preferences.

*Alternative considered:* reuse Boot's `BuildProperties` and `GitProperties` beans directly. Rejected as
the carrier, because they are beans available only after the context refreshes, and the encoder is
installed at `ApplicationEnvironmentPreparedEvent` — long before. They are the right *source*, so
`BuildIdentityResolver` reads the same two classpath resources those beans are built from.

### D2 — Resolve provenance from classpath resources, at `EnvironmentPostProcessor` time

`BuildIdentityResolver` reads `git.properties` and `META-INF/build-info.properties` from the classpath
and resolves each field independently, preferring an explicit `ludwig.observability.build.*` property
where one is set — the same precedence shape `ServiceIdentityResolver` already uses for version.

`ObservabilityEnvironmentPostProcessor` publishes the resolved values into its existing
`ludwig-observability-defaults` property source, so everything downstream — the encoder, the startup
event, `/actuator/info` — reads them through `Environment` rather than each re-parsing the resources.

*Why there:* it is the one place that runs before the logging system is reconfigured and already owns
exactly this job for `ServiceIdentity`. Adding a second resolution point would be a second mechanism.

*Consequence to accept:* resource parsing moves into the startup path before the context exists, so a
malformed resource must not abort the boot. The resolver catches and treats a parse failure as "all
fields absent", which is the same outcome as an artifact built without git — a state the spec already
requires to be survivable.

### D3 — The profile-dependent default is computed in the post-processor

The default for `ludwig.observability.logging.json.enabled` becomes
`!environment.getActiveProfiles() contains "local"`, computed in
`ObservabilityEnvironmentPostProcessor` and published into the same lowest-precedence source.

*Why this works and is not a precedence trick:* the post-processor declares no order, so it runs after
config-data processing, when `spring.profiles.active` is resolved. And because the value is published
with `addLast`, any explicit setting — `application.yml`, an environment variable, a system property —
still wins. The existing manual overrides in service templates therefore keep working untouched, which
is a spec scenario.

*Why not a `@ConditionalOnProperty` / profile-scoped `@Configuration`:* the format is chosen by
`JsonLoggingInitializer`, which is not a bean and runs before any condition is evaluated.

*Alternative considered:* a three-valued `format: auto | text | json` property with `auto` as the
default. Honest and self-documenting, but it changes the type of a published property — breaking for any
deployment that already sets the boolean, which the README tells every service to do. Rejected on that
ground alone.

*Hard-coding `local`:* the profile name is a constant, not a property. Making it configurable would
invite a deployment to name production's profile and get text logs in production, which is the failure
this default exists to prevent. The constant carries a comment saying so — `encode every rule twice`,
discharged by a comment where no check is possible.

### D4 — One field declaration drives both formats

`LogFieldNames` gains a 20th component, `commitId`, named per field set: `service.commit.id` for `ecs`
(dotted, ECS custom-field convention), `ServiceCommitId` for `otel` (pascal, matching `TraceId`),
`commit_id` for `flat` (snake).

For the human-readable path, `JsonLoggingInitializer` — when the format resolves to text — sets a
Logback pattern built from the resolved identity instead of leaving Boot's default. The identity values
are literals in the pattern string, because they are per-process constants; the correlation, trace and
span ids are `%mdc{...}` conversions, because they are per-event.

*Why not a custom `Converter`:* registering a conversion word requires a Logback configuration file,
which is the thing services are not supposed to need.

*Trade-off accepted:* the text pattern and the JSON field sets are two places that must both be updated
when a mandatory field is added. The ArchUnit rule cannot see that. A unit test asserting both paths
emit every field the spec calls mandatory is the check, and it lives next to the existing
`JsonLogEncoderTest`.

### D5 — Enforcement, split across the triad as the triad requires

| Rule | Owner | Mechanism |
|---|---|---|
| No module but `observability-spring-boot-starter` implements a Logback `Encoder`/`Layout` | ArchUnit | new `RuleGroup.LOGGING` |
| No second type holds build provenance | ArchUnit | `RuleGroup.LOGGING`, restatement rule shaped like `RuleGroup.OPERATIONS` |
| No production source invokes a git process or reads a `.git` path | ArchUnit | `RuleGroup.LOGGING` |
| `ludwig-service-parent` actually configures both plugins to emit their resources | gate script | new `scripts/check_build_metadata.sh` + `.py`, registered in `gate.sh`'s `COMMANDS` |
| The packaged resources exist and carry a commit id | test | integration test in `crud-service-example` |
| Both formats emit every mandatory field | test | unit test in `observability-spring-boot-starter` |

*Why the plugin check is a script, not ArchUnit or Checkstyle:* it is a fact about POM text and generated
resources. ArchUnit sees bytecode and Checkstyle sees Java source; neither can see whether a plugin goal
is bound. This is the identical reasoning that puts `check_migrations` and `check_image_pins` in
`scripts/`, and it keeps the triad non-overlapping.

*Why a service-level integration test as well as the script:* the script checks the configuration, the
test checks the outcome. The drift this change fixes was precisely a configuration that looked right in
prose and produced nothing. Naming the test `…IntegrationTest` is mandatory or surefire excludes it and
failsafe never picks it up.

*No Checkstyle rule is added.* Nothing here is a source-text fact.

### D6 — Dependency-direction check

**Does any module this change touches gain a new in-repo dependency?** No.

Written out as the rule requires: the only candidate edge would be
`observability-spring-boot-starter` → a module supplying build metadata. There is no such module; the
metadata arrives as classpath resources generated by `ludwig-service-parent`, which is a **parent**, not
a dependency, and `observability-spring-boot-starter` is not parented by it (it is a library, parented by
the reactor root `common`). So no edge is added.

For completeness, the existing edges and the cycle question:

- `observability-spring-boot-starter` `inRepoDependencies` = [`audit-core`, `hot-reload-spring-boot-starter`,
  `web-core-spring-boot-starter`] — unchanged by this design.
- `observability-spring-boot-starter` `inRepoDependents` = [`export-spring-boot-starter`,
  `file-action-spring-boot-starter`, `notification-service`, `reconciliation-spring-boot-starter`,
  `rest-client-spring-boot-starter`, `user-settings-spring-boot-starter`]. None of these is added as a
  dependency by this change, so no cycle is possible.
- `architecture-rules` gains a `RuleGroup` constant and rule sources. Does `architecture-rules` already
  depend on `observability-spring-boot-starter`, directly or transitively? Its
  `inRepoDependents` list is [`crud-service-example`, `file-action-spring-boot-starter`,
  `file-ingest-spring-boot-starter`, `messaging-spring-boot-starter`, `notification-service`,
  `object-storage-spring-boot-starter`, `odata-filter-spring-boot-starter`, `pat-spring-boot-starter`,
  `user-settings-spring-boot-starter`], which does **not** contain
  `observability-spring-boot-starter`; and the new rules reference Logback and git-path types by
  **string name**, as the existing rule groups do, so no compile dependency is introduced in either
  direction. No cycle.
- `crud-service-example` already depends on `architecture-rules` (test scope). The new integration test
  adds no dependency.

### D7 — Which of the three POMs changes

**`ludwig-service-parent` only.** It is where services' build decisions live, and generating provenance
into a service artifact is exactly that: `generateGitPropertiesFile` plus the properties to emit on
`git-commit-id-maven-plugin`, and a bound `build-info` execution on `spring-boot-maven-plugin`.

- **`pom.xml` (root): no change.** No library gains a plugin or a plugin version. Libraries are not
  packaged as services and get no provenance resource of their own.
- **`ludwig-bom`: no change.** No third-party version is added or moved. Both plugins are already
  configured, with versions already managed.

Because `ludwig-service-parent` is edited, **the verification gate for this change is
`scripts/gate.sh --full`** — `mvn clean install` over the whole reactor. `project-index.json` reports no
dependents for a parent because inheritance is not a dependency edge, so a narrow `-pl` build would
verify almost nothing. `gate.sh` already substitutes the full build when a bom or parent module is named.

### D8 — The build timestamp versus reproducible image digests

`build-info.properties` carries a build time by default, and it lands in a jar layer that jib hashes.
Two commits' worth of identical source would then produce different digests, destroying the "this digest
is that commit" property jib's epoch timestamps exist to provide.

**Decision: emit the build time truncated to the day** (`build.time` reduced to date precision) via the
plugin's `build.time` property override.

*Rationale:* it keeps a build timestamp, which the spec requires, while making the digest stable for any
two builds of one commit on the same day — which covers the case the property actually serves, namely a
CI retry or a local reproduction check. It does not make the digest stable across a day boundary, and
that limit is stated in the parent POM comment beside the setting rather than left for someone to
rediscover.

*Alternatives considered:* (a) omit the build time entirely — satisfies reproducibility perfectly and
loses a field the spec requires; (b) keep full precision and accept digest drift — silently removes a
documented, load-bearing property of the build; (c) set it from the commit timestamp — fully reproducible
and genuinely appealing, but it then answers "when was this committed", which `git.properties` already
answers, and a field named build time that is not the build time is worse than a coarse one.

## Risks / Trade-offs

**`/actuator/info` starts disclosing branch name and commit id.** `info` is already in the default
`management.endpoints.web.exposure.include`, so this becomes reachable wherever the management port is.
→ Mitigation: it is a deliberate, documented consequence recorded in the proposal's *Impact*, and it is
the branch and commit — not credentials or memory contents, which is why `env`, `configprops`,
`heapdump` and `threaddump` remain excluded. A deployment that cannot disclose it narrows the include
list, which is one documented exclusion in one place.

**A developer under the `local` profile silently loses JSON logs.** Someone who had come to rely on
local JSON output gets text and may not notice the default changed.
→ Mitigation: the behaviour is a spec scenario, and the property to restore it is unchanged and already
documented. Called out in the proposal's *Impact* as a developer-visible change.

**A log aggregator with a strict index template rejects the new commit field.** Adding a structural field
to a shared index can fail ingestion for every service sharing it.
→ Mitigation: the field name is published per field set before rollout, and the field is a short keyword
string in all three. Ordering is a deployment concern, recorded in the migration plan below.

**The text pattern and the three JSON field sets drift.** Four places now encode what fields are
mandatory, and no architectural rule can see all four.
→ Mitigation: the D4 unit test asserts every mandatory field in both paths. It is the check the *encode
every rule twice* convention demands, and the only reason the trade-off is acceptable.

**Resource parsing in the startup path.** `BuildIdentityResolver` runs before the context exists, where
an exception is a failure to boot.
→ Mitigation: parse failure is caught and degrades to "all fields absent", which the spec already
requires to be a survivable state, with a unit test for a malformed resource.

**Build time truncated to the day is a half-measure.** It bounds digest drift rather than eliminating it.
→ Mitigation: explicit in D8 and in a comment at the setting. The alternative that eliminates drift
drops a field the spec requires.

## Migration Plan

1. **`ludwig-service-parent` first**, on its own: switch on both plugins' resource generation, with the
   day-precision build time. At this point artifacts carry provenance and `/actuator/info` grows two
   sections; nothing reads it yet. Correct the two drifted comments in the same edit.
2. **Add the gate script and register it** in `gate.sh`, so step 1 cannot silently regress while the rest
   is being built.
3. **`observability-spring-boot-starter`**: `BuildIdentity`, `BuildIdentityResolver`, the post-processor
   wiring, the `LogFieldNames` component, the encoder change, the text pattern, the startup event.
4. **Publish the new field names** to whoever owns the log index template, and have the mapping added,
   *before* the first service carrying step 3 reaches an environment that shares an index. This is the
   one ordering constraint with anything outside the repository.
5. **`architecture-rules`**: `RuleGroup.LOGGING` and its rules. Last, because a rule added before the
   code it governs fails the build of every module in between.
6. **`crud-service-example`**: the provenance integration test.
7. **Full gate**: `scripts/gate.sh --change add-build-identity-logging --full`.

**Rollback.** Every piece is independently revertible and none involves persisted state or a schema, so
there is no migration to undo.

- A service needing the old console behaviour sets
  `ludwig.observability.logging.json.enabled: true` explicitly — no redeploy of the platform.
- The new log field is additive; a consumer that does not know it ignores it.
- Reverting step 1 alone returns artifacts to carrying no provenance, at which point the resolver reports
  every field absent and the log stream loses one field. It does not fail.

The only non-revertible step is 4, and only in the sense that an index template mapping, once added, is
tedious to remove — it is harmless to leave in place.

## Open Questions

- **The `ecs` field name for the commit.** `service.commit.id` is proposed because ECS has no standard
  field for it and the `service.*` namespace is where the module's other identity fields already live.
  ECS does define `git.commit.id` in some community mappings. Either works; the choice is deferrable
  because it changes one string in one factory method and no requirement, and is best settled with
  whoever owns the index template as part of step 4.
- **Whether `notification-service` should also carry the provenance integration test.** The spec requires
  the check to exist, not that it exist twice. `crud-service-example` is the designated reference service,
  so one test there discharges it; adding a second is a judgement about redundancy that can be made later
  without touching specs or tasks.
