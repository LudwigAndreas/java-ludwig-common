## Why

Running `notification-service` whole against real stand-ins (change
`adopt-local-run-in-notification-service`) found four defects in other modules. One is a security
gap in every deployment configured by `issuer-uri`; one stops a documented feature from starting at
all; two make test suites fail in ways that point away from the cause. Each is pinned or worked
around in that change today, and each workaround should go.

## What Changes

- **`security-spring-boot-starter`: the audience check is enforced whatever the decoder's type.**
  It is installed today only on a `NimbusJwtDecoder` bean. With `issuer-uri`, Spring Boot's decoder
  bean is a lazy `SupplierJwtDecoder`, so `ludwig.security.jwt.audiences` is silently not enforced
  and a token the same issuer minted for another service is accepted.
- **`identity-projection-spring-boot-starter`: its Kafka consumer starts.** The listener container
  factory is guarded by `@ConditionalOnBean(ListenerContainerFactoryBuilder)` but the
  auto-configuration is not ordered after the one that registers that builder, so the factory is
  skipped and the listener fails on a missing bean.
- **`user-settings-spring-boot-starter`: present with no mode enabled, it stays out of the way.**
  Its hot-reload auto-configuration is active whenever hot-reload is, and then requires a cache
  only an enabled mode creates.
- **`test-support`: a context cannot stop a shared container.** The per-JVM singletons ignore
  `stop()`, so a context that closes, or fails to load, no longer takes the database away from
  every other context in the JVM.
- **`notification-service` (test sources only): the workarounds are removed.** The identity
  projection's consumer is on in the local profile; the pinned "wrong audience is accepted" test
  asserts 401; the build's launcher no longer sets a user-settings property a developer's run does
  not.

## Non-goals

- **`rest-client-spring-boot-starter`'s not-really-optional dependencies** (`spring-security-oauth2-client`,
  `spring-webflux`). Fixing that means restructuring its auto-configurations; it is a separate change.
- **The suite's closeness to PostgreSQL's connection limit.** The 20-connection pool per cached
  test context is `notification-service`'s own setting and is not changed here.
- **Any change to how audiences are configured.** The property, its default and
  `require-audience` behave as before; only whether the check runs changes.
- **Replacing `PostgresContainerConfiguration` or `test-support-local`'s `LocalPostgresConfiguration`.**
  Both keep working as they are.
- **The Russian README of `notification-service` being three headings short.**

## Capabilities

### New Capabilities

- `jwt-audience-validation`: that a configured audience is enforced on every bearer token.
- `starter-conditional-wiring`: that a starter's conditional beans do not depend on being evaluated
  in a lucky order, and that a starter with nothing enabled does not break start-up.
- `shared-test-containers`: that the per-JVM test containers outlive any application context.

### Modified Capabilities

None. `notification-local-run`, whose two unmet requirements these fixes satisfy, belongs to a
change that is not archived yet; its requirements are not edited here.

## Impact

**Shared contracts in `openspec/specs/`.** None is changed. Read and complied with:
`module-dependency-direction` (no dependency is added), `enforcement-triad`, `test-layout`,
`container-image-pinning` (image references and `LudwigTestImages` are untouched).

**Modules touched, tier, and in-repo dependents** (from `scripts/manifest.sh module`):

| Module | Tier | What changes | In-repo dependents |
|---|---|---|---|
| `security-spring-boot-starter` | library, parent `common`, imports `ludwig-bom` | audience post-processor, a test | 8 |
| `identity-projection-spring-boot-starter` | library | one auto-configuration's ordering, a test | 2 |
| `user-settings-spring-boot-starter` | library | one auto-configuration's conditions, a test | 1 (`notification-service`) |
| `test-support` | library | `Containers`, a test, README | 17 |
| `notification-service` | service, parent `ludwig-service-parent` | test sources and test resources, README | none |

No dependency is added or removed and no POM changes.

**Gate.** `test-support` and `security-spring-boot-starter` are depended on by nearly every module,
so the gate is in effect the whole reactor: `mvn clean install`. `scripts/gate.sh` includes
`scripts/check_api_baseline.sh`, which cannot pass on a machine that cannot reach the releases
repository.

**Behaviour change for a deployment.** A service that sets `ludwig.security.jwt.audiences` and is
configured by `issuer-uri` starts rejecting tokens for other audiences with 401. That is the
configured intent; a caller that relied on the gap breaks. It is called out in the changelog.
