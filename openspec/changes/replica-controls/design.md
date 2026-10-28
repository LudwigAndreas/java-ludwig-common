## Context

Two modules gain an administrative area each, through optional dependencies on `job-core` (the `ReplicaControl`
SPI) and `security-spring-boot-starter` (operations, catalog, evaluator). This adds in-repo edges, so the direction
check below is mandatory. `replica-control-broadcast` must be applied first.

## Goals / Non-Goals

**Goals:**
- Each module owns its own controls, because it alone knows its state.
- Nothing in either module changes when the administrative surface is absent.

**Non-Goals:** see the proposal.

## Decisions

1. **Controls live in the owning module, behind conditions.** This is unlike `caches`, which is hosted in
   `job-core` only because `cache-spring-boot-starter` must have zero in-repo dependencies. `hot-reload` and
   `rest-client` carry no such rule. Each gets an `admin` package (`<X>AdminOperations`, `admin/service`,
   `admin/web`) and an auto-configuration, matching every other area. The auto-configuration is
   `@ConditionalOnClass(name = {"…AdminOperationCatalog", "…ReplicaControl"})`, `@ConditionalOnBean`, and
   `afterName = AdminAutoConfiguration`.
2. **hot-reload needs a registry of sources.** Sources are created by auto-configurations and handed to
   `ResourceWatcherLifecycle`; nothing indexes them by id. A `ReloadableSourceRegistry` bean is filled where each
   source is created. The control calls `SourceReloadCoordinator.reload(source)`, which already does diff,
   last-known-good, audit and listeners, so a forced reload is indistinguishable from a watched one.

   The per-replica result is `reloaded`, `unchanged` or `rejected`:
   - `reload` returns whether content changed;
   - `notifyError` marks a rejection.

   "Last successful reload" in the listing is local to the answering replica, and the response says so in its
   field name (`lastReloadedHere`).
3. **rest-client needs a registry of token sources and the breaker registry.** `ClientCredentialsAuthenticationProvider`
   creates a `CachingTokenSource` per client. A `ClientTokenSources` bean records them by client name. The reset
   calls the existing `invalidate()`. Breakers come from the existing `ResilienceRegistries.circuitBreakers()`;
   setting a state uses Resilience4j's `transitionToClosedState`, `transitionToForcedOpenState` and
   `transitionToDisabledState`.
4. **Tiers.**
   - Reload and token reset are `operate`. Both are safe to repeat and do not change who may access what.
   - Forcing a breaker is `privileged`, because it can put this service out of agreement with a partner (calls
     stop being made). That matches the tier definition in `admin-authorization`.
   - Reading breakers is a control, not a GET, because the state is per replica. It is `read`, and it changes
     nothing.

**Dependency-direction check.**
- *Does `job-core`, `security-spring-boot-starter` or `web-core-spring-boot-starter` already depend on
  `hot-reload-spring-boot-starter`, directly or transitively?* `project-index.json`'s `inRepoDependents` for
  `hot-reload-spring-boot-starter` is `[export-spring-boot-starter, notification-service,
  observability-spring-boot-starter, user-settings-spring-boot-starter]`. Following those transitively reaches
  `rest-client-spring-boot-starter`, `crud-service-example`, `reconciliation-spring-boot-starter` and
  `test-support-local`. None of these is `job-core`, `security-spring-boot-starter` or
  `web-core-spring-boot-starter`, so there is no cycle.
- *Does `job-core` or `security-spring-boot-starter` already depend on `rest-client-spring-boot-starter`?*
  `inRepoDependents` for `rest-client-spring-boot-starter` is `[crud-service-example, export-spring-boot-starter,
  reconciliation-spring-boot-starter, test-support-local]`. It contains neither, and none of those is depended on by
  either, so there is no cycle.

**POMs.** Only `sources/hot-reload-spring-boot-starter/pom.xml` and `sources/rest-client-spring-boot-starter/pom.xml`
change, gaining optional dependencies. The root `pom.xml`, `ludwig-bom` and `ludwig-service-parent` are unchanged.

**Mechanical checks.** No new convention beyond the one `replica-control-broadcast` introduced and already enforces
at startup: unique control names, and operations that exist in the catalog. Each area adds the fail-closed
auto-configuration test every area has.

## Risks / Trade-offs

- [Optional dependencies make the classpath matrix wider.] → Each module's auto-configuration test covers both
  shapes: without `job-core` (no area, no failure) and with it.
- [A forced-open breaker is forgotten.] → The breaker listing shows `FORCED_OPEN` per replica, and the audit trail
  names who did it and why. A restart resets it, because Resilience4j state is in memory, and the README states
  that.
