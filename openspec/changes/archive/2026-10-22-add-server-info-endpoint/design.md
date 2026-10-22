## Context

See `proposal.md` for motivation. The facts that shape the approach:

- `ObservabilityCoreAutoConfiguration` already publishes two beans, `ServiceIdentity` (name,
  namespace, version, environment, instance) and `BuildIdentity` (commit id, abbreviated commit id,
  branch, build timestamp, CI build number, dirty). Both blank-to-null their components, so "absent"
  is already `null` at the source.
- `ObservabilityWebAutoConfiguration` is the starter's servlet-only configuration
  (`@ConditionalOnWebApplication(SERVLET)`, gated on `ludwig.observability.enabled`). The starter
  ships no controller today; `spring-webmvc` and `spring-web` are optional dependencies of it.
- Anonymous access in this platform is a property list, `ludwig.security.public-paths`, owned by
  `security-spring-boot-starter`. `notification-service` overrides that list in its
  `application.yml`, so a changed default would not reach it anyway.
- A service's HTTP surface is committed under `docs/api/` and compared at `verify`
  (`published-api-document`), so a starter-contributed path changes a service's committed document.
- `crud-service-example` does not have the observability starter on its classpath. `export`,
  `file-action`, `rest-client`, `reconciliation` and `user-settings` all declare it `optional`, and
  the service does not declare it itself. `notification-service` is the only in-repo service that
  has it.
- `crud-service-example` declares no `ludwig.security.public-paths` and so runs on the security
  starter's default list (`/actuator/health`, `/actuator/health/**`, `/actuator/info`). It does set
  `management.endpoints.web.exposure.include` itself.
- The starter's `ObservabilityEnvironmentPostProcessor` contributes defaults the moment it is on a
  classpath: JSON console logging unless the `local` profile is active, banner off under JSON,
  health probes, graceful shutdown, tracing sampled at 0.1, and observation on Kafka templates and
  listeners. Every one of them yields to an explicit property.

## Goals / Non-Goals

**Goals:**

- One endpoint, one shape, contributed by the starter that already owns the data.
- A response that is safe to serve anonymously by construction, not by review.
- No new module, no new in-repo dependency, no second copy of identity resolution.

**Non-Goals:**

- A shared contract type in `web-core`. There is one producer and no in-repo consumer; a contract
  split is for when a second producer appears.
- Cache headers, ETags or polling guidance. The values are constant for a process's lifetime and a
  footer reads them once per page load.

## Decisions

### D1. The endpoint lives in `observability-spring-boot-starter`

Both records it reads are beans of that starter. Serving them from there needs no new dependency.

*Alternative - `web-core-spring-boot-starter`:* it is on every service's classpath, which would
solve reach, but it has zero in-repo dependencies and the data is not there. It would need either a
dependency on observability, which is a cycle, or an SPI that observability implements - a second
type restating the identity for one caller. Rejected.

*Alternative - a new `server-info` module:* a module for one controller and one record.

**Dependency-direction check.** The controller itself adds no in-repo dependency: it depends on
`ServiceIdentity`, `BuildIdentity` and Spring MVC only, all already on
`observability-spring-boot-starter`'s compile path. `notification-service` already depends on
`observability-spring-boot-starter` (compile). The change adds exactly one edge,
`crud-service-example` → `observability-spring-boot-starter` (D8). Does
`observability-spring-boot-starter` already depend on `crud-service-example`, directly or
transitively? `project-index.json` `inRepoDependents` for `crud-service-example` is empty - nothing
in the repository depends on it - so no path leads back and no cycle is possible.

**POMs.** None of the three changes - not the root `pom.xml`, not `ludwig-bom`, not
`ludwig-service-parent`. `sources/observability-spring-boot-starter/pom.xml` gains one optional
dependency, `jackson-annotations`, with no version (managed by `spring-boot-dependencies`). The
starter declares only `jackson-core` today; D4 uses an annotation directly, and a directly used
dependency is declared rather than inherited by accident from whatever else brings it in.
`services/crud-service-example/pom.xml` gains the starter as a compile dependency with no version
(managed by `ludwig-bom`).

### D2. A dedicated response record, mapped field by field

`ServerInfoResponse(service, version, environment, commit, built)` is constructed by naming each
source component. Neither identity record is ever serialized.

This is what makes the allow-list structural. Serializing `BuildIdentity` and hiding fields would be
a deny-list: the day someone adds a component to that record, it would be public. With an explicit
mapping a new component is invisible until someone writes the line that exposes it.

*Alternative - return a `Map<String, String>`:* omits absent keys for free, but springdoc then
publishes a free-form object, and the committed OpenAPI document would say nothing about the shape a
UI is coding against.

### D3. `built` is a date, derived in UTC

`BuildIdentity.buildTimestamp` is ISO-8601 instant text that the platform truncates to the day. The
response parses it as an instant and renders the UTC calendar date. The zone is the constant
`ZoneOffset.UTC`, not the JVM default, so `RuleGroup.PRESENTATION` is not engaged, and not the
caller's zone either: this is a fact about the build, not a moment shown to a user.

Text that does not parse as an instant - possible for an artifact built outside the service parent -
makes `built` absent. It does not fail the request and is not passed through verbatim, because
`built` is specified as a date.

### D4. Absent members are omitted by the serializer

`@JsonInclude(NON_NULL)` on the record. An all-absent identity therefore serializes to `{}` with
status 200. A 404 or 204 there would make "this service has no provenance" indistinguishable from
"this service has no such endpoint", which is exactly the distinction a per-service footer popover
needs.

### D5. Registered as a bean, switched by one property, path fixed

The controller is a `@Bean` in `ObservabilityWebAutoConfiguration`, `@ConditionalOnMissingBean` and
`@ConditionalOnProperty(prefix = "ludwig.observability.server.info", name = "enabled",
matchIfMissing = true)`, with the property declared on `ObservabilityProperties` so it has metadata.
It inherits the servlet and `ludwig.observability.enabled` conditions of that configuration.

The path `/server/info` is a constant on the controller. Other starters make their base path a
property (`ludwig.pat.web.base-path`, `ludwig.user-settings.web.base-path`); this one deliberately
does not. Those are per-service resources. This is a cross-service well-known path, and a
configurable one stops being well-known. A service that already maps `/server/info` switches the
platform's off.

### D6. Access is the service's `public-paths` entry, not a platform default

The starter adds no security configuration. `notification-service` appends `/server/info` to its
existing `ludwig.security.public-paths`.

`crud-service-example` has no such block, so it gains one - and it must restate the three default
actuator entries beside `/server/info`. A list bound from YAML replaces the default list rather than
extending it, so a block holding only the new path would silently put the health probes behind
authentication. The block carries a comment saying so, and the service's integration test asserts
that `/actuator/health` still answers without credentials next to the assertion for `/server/info`.

*Alternative - add `/server/info` to `SecurityProperties`' default list:* it would widen the
anonymous surface of every consumer on upgrade, in a module with eight in-repo dependents, and still
miss any service that overrides the list - which `notification-service` does.
The payload being safe to publish is the platform's job (D2); deciding to publish it is the
service's.

### D7. Enforcement

| Rule introduced | Check | Owner |
|---|---|---|
| The body carries exactly the five members and never branch, dirty, CI build number, full commit id, namespace or instance | Unit test serializing a response built from fully populated identities and asserting the exact member set | test suite of `observability-spring-boot-starter` |
| Absent values are omitted, all-absent is `{}` | Same unit test class | same |
| The endpoint exists on a servlet app, is absent when switched off and on a non-servlet app | Context test beside `ObservabilityAutoConfigurationTest` | same |
| Each service serves it anonymously | Integration test (`ServerInfoIT`) with no credentials | `notification-service`, `crud-service-example` |
| Restating `public-paths` did not close the health probes | Same `ServerInfoIT`, asserting `/actuator/health` without credentials | `crud-service-example` |
| The path is in each service's published surface, and the starter stays on the reference service's compile path | Existing `ApiDocumentIT` comparison: removing the dependency drops the path and fails the build | `notification-service`, `crud-service-example` |

No ArchUnit or Checkstyle rule is added. The allow-list is a fact about one class's serialized
output, which is neither a structural dependency nor source text; a test on that output is the check
that can see it. A comment at the record states that the member set is specified by the
`server-info` capability and pinned by that test.

### D8. `crud-service-example` declares the starter directly and takes its defaults

The service adds `observability-spring-boot-starter` as a compile dependency, the way
`notification-service` does. It sets no `ludwig.observability.*` property: the starter's defaults
are the platform's stated position, and a reference service that overrides them teaches the
override.

This is more than the endpoint. The starter's environment defaults apply, its filters register, and
the integrations in `rest-client-spring-boot-starter`, `export-spring-boot-starter` and
`file-action-spring-boot-starter` that are conditional on the starter's presence become active. That
is accepted rather than avoided: `service-log-stream` already describes the stream every service
writes, and the reference service not writing it was the anomaly.

*Alternative - make one of the starters the service already uses export observability
non-optionally:* it would reach the service with no POM edit there, and would force MVC-free
consumers of that starter to carry tracing and a log encoder. The optional declarations are
deliberate; the service is the right place to opt in.

*Alternative - move the endpoint to a module the service already has:* rejected under D1.

### D9. In `rest-client-spring-boot-starter`, the platform call context is registered first and the fallback backs off

`RestClientAutoConfiguration` declares `ludwigRestClientCallContextSource` (the `NONE` fallback)
under `@ConditionalOnMissingBean`. `RestClientObservabilityAutoConfiguration` is ordered *after* it
and declares `ludwigRestClientPlatformCallContextSource` with no such condition. A missing-bean
condition only sees what was registered earlier, so the fallback always registers, the platform
source is added beside it, and `ludwigClientRuntimeBuilder` - which takes one `CallContextSource` -
fails the context with `NoUniqueBeanDefinitionException`.

The fix is the ordering, plus the condition the second bean never had:

- `RestClientObservabilityAutoConfiguration` becomes `before = RestClientAutoConfiguration`, and
  names observability's core auto-configuration in `afterName`. Its `@ConditionalOnBean(
  CorrelationContext.class)` has only ever held because `ru.ludwigandreas.observability` sorts
  before `ru.ludwigandreas.restclient` alphabetically; that is now stated rather than relied on.
- The platform source gains `@ConditionalOnMissingBean(CallContextSource.class)`.

Result: with observability, the platform source registers and the fallback backs off; without it,
or with `ludwig.observability.enabled=false`, only the fallback registers; an application that
declares its own source gets that one and neither of the starter's.

Nothing else in that auto-configuration depends on following the main one. Its other beans are
conditional on classes, properties and observability's beans, not on anything
`RestClientAutoConfiguration` registers, and `RestClientProperties` is injected at instantiation,
which does not depend on definition order.

*Alternative - `@Primary` on the platform source:* one line, but it would also outrank a source the
application declared itself, turning an extension point into a trap.

*Alternative - move the fallback into the observability auto-configuration, after its nested
class:* correct, but it removes a public `@Bean` method from a published class for no gain over
reordering.

*Alternative - work around it in `crud-service-example`* (exclude the integration, or declare a
`@Primary` source there): the reference service would then demonstrate a workaround for a platform
defect, and the next consumer would hit the defect anyway.

**Dependency-direction check for this module:** no dependency is added.
`rest-client-spring-boot-starter` already depends on `observability-spring-boot-starter` (compile,
optional); `afterName` is a string and adds no edge.

**Enforcement.** A test in `rest-client-spring-boot-starter` boots the starter's auto-configurations
together with observability's core auto-configuration and asserts a single `CallContextSource`, of
the platform type; a second asserts that an application-declared source is the only one. It is a
test and not an ArchUnit rule because the defect is a property of the assembled context, which
neither bytecode structure nor source text shows.

## Risks / Trade-offs

- **[Reordering an auto-configuration changes behaviour for existing consumers of the rest-client
  starter]** → For a consumer without observability nothing changes: the nested configuration is
  never loaded and the fallback registers as before. For a consumer with it, the previous behaviour
  was a context that did not start. The gate runs all three in-repo dependents.

- **[The reference service's existing integration tests now run with tracing, correlation and the
  starter's logging on]** A span exporter with no collector to reach, or a filter ordered ahead of
  the test security configuration, could fail or slow tests that have nothing to do with this
  change. → A task runs the service's full `verify` immediately after the dependency is added and
  before anything else is touched, so a failure is attributable to the dependency alone. If one
  appears it is fixed by configuration in the service's test setup, never by narrowing the starter.
- **[`crud-service-example`'s console output changes format outside `local`]** → Intended, and
  stated in the proposal, the service README and the changelog entry. `local` keeps text.
- **[Starter-contributed behaviour alters the reference service's published API document beyond the
  new path]** → The refresh task requires reading the diff and names what it may contain; anything
  else is a finding to record, not to commit past.
- **[A UI polls the endpoint and it shows up in traces and HTTP metrics]** → The values do not change
  while a process lives, and the README says to read it once per page load. A deployment that sees
  the noise adds the path to `ludwig.observability.tracing.excluded-paths` and
  `ludwig.observability.metrics.http.ignored-paths`; the defaults are left alone.
- **[Version and commit become anonymously readable on both services]** → Already true:
  `/actuator/info` is in both services' public paths and discloses more. This endpoint narrows what a
  browser needs to be routed to, it does not widen what is disclosed.
- **[During a rolling deploy two replicas answer differently]** → Correct behaviour; each replica
  reports the build it is. The instance is withheld, so a UI cannot tell replicas apart and should
  not try.
- **[A consumer upgrades and gains an unrequested endpoint]** → It is authenticated unless the
  service opts it into `public-paths`, and one property removes it. The changelog entry names both.

## Migration Plan

Additive. Rollback is reverting the commit; no data, schema or configuration is left behind. A
service that must not serve it sets `ludwig.observability.server.info.enabled=false`.
