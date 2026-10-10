## Why

Debugging a service on a laptop today means running an amputated one: `crud-service-example`'s
`application-local.yml` switches Kafka off, stops the outbox poller and has no partner to call, so
the code path under the breakpoint is not the one production runs. The primitives for a whole
local service already exist in `test-support` (digest-pinned containers, `WireMockFixture`,
`TestClock`, `DatabaseCleaner`), but nothing composes them into a runnable application, and every
partner stub is a private class inside one integration-test package (`SupplierDirectoryStub`), so
it can serve a test and nothing else.

## What Changes

- **New module `test-support-local`** (library tier, `sources/`, package
  `ru.ludwigandreas.testsupport.local`). The change directory keeps the working name
  `add-ludwig-test-fixtures`; the artifactId takes the `test-support` prefix because
  `scripts/project_index.py` assigns the `test-support` role by that prefix.
- **A local launcher.** A service's `Local<Name>Application` in `src/test` starts the service's
  real `main` with the backing services it names (PostgreSQL, Kafka, LocalStack, Redis from
  `test-support`'s pinned singletons), its partner stubs and its seed fixtures, then logs what is
  running and where.
- **Partner stubs keyed by rest-client name.** A stub is a real HTTP server for one
  `@LudwigRestClient` name. The module points `ludwig.rest-client.clients.<name>.base-url` at it,
  so the real client, pool, timeouts, retry and circuit breaker all run. One stub definition serves
  both an integration test and the local launcher.
- **Fault switching on a stub**: unavailable, slow, connection reset, empty response, refused
  connection, per stub and optionally per path. Faults are settable from test code, from a
  start-up property, and over HTTP while the service is paused at a breakpoint.
- **Seed fixtures.** An ordered fixture contract run after migrations, writing through the
  service's own beans, with an explicit reset/keep/none mode; Kafka seeding and a
  duplicate-delivery helper.
- **Two build-failing rules.** Every `@LudwigRestClient` in a service that has a local application
  has a stub or an explicit pass-through declaration (ArchUnit rule shipped by this module, and the
  launcher refusing to start). No `test-support*` artifact may reach a service's compile or runtime
  classpath (enforcer rule in `ludwig-service-parent`).
- The module carries its own sample application and integration tests, because no service consumes
  it in this change and an unbooted launcher is an untested one.

## Non-goals

- **No `Local*Application` for `notification-service` or `crud-service-example`.** That is the next
  change; this one touches neither service's sources.
- **No clock seam.** Replacing no-argument `now()` calls in `notification-service`,
  `reconciliation-spring-boot-starter` and `outbox-spring-boot-starter` is production-code work and
  a rule of its own.
- **No deterministic-ID seam.** `UUID.randomUUID()` call sites in main code have no platform
  abstraction to substitute, and adding one is a "one mechanism" decision for a core module.
- **No network-level fault injection** for PostgreSQL or Kafka. That needs a proxy container, which
  is another pinned image and a separate change.
- **No "table used implies fixture defined" rule.** The fixture contract ships; the coverage rule
  waits until two real services have fixtures to design it against.
- **No embedded PostgreSQL or Kafka.** Containers only.
- **No stateful fakes shipped.** The stub contract permits one; the module ships canned answers and
  fault switching.
- **No change to `test-support`'s code or dependencies**, and no new container image.

## Capabilities

### New Capabilities

- `local-run`: starting a service's real entry point on a developer machine with its backing
  services, stubs and fixtures, and what that launcher refuses to do.
- `partner-stub`: the contract for an HTTP stand-in for one named rest client - wiring, sharing
  between tests and the launcher, fault switching, and the client-implies-stub rule.
- `seed-fixture`: the contract for seed data and seeded or duplicated messages - ordering, mode,
  and how a fixture writes.

### Modified Capabilities

- `enforcement-triad`: adds the ownership of the two new checks (the module-shipped ArchUnit rule
  and the `ludwig-service-parent` enforcer rule).

## Impact

**Shared contracts in `openspec/specs/`.** `enforcement-triad` gains requirements. Read and
complied with, not changed: `module-dependency-direction` (direction check is in the design),
`repository-layout` (the module is a test-support module in `sources/`; `jacoco-aggregate` must
list it), `container-image-pinning` (images come from `LudwigTestImages` only), `pom-topology`,
`test-layout` (a local application is not a test, so the three-directory rule is untouched).

**Modules touched, tier, and in-repo dependents** (from `scripts/manifest.sh module`):

| Module | Tier | What changes | In-repo dependents |
|---|---|---|---|
| `test-support-local` (new) | library, parent `common`, imports `ludwig-bom` | the whole module | none |
| `ludwig-bom` | bom | one managed-dependency entry for the new artifact | every module |
| `ludwig-service-parent` | parent | one enforcer rule | `crud-service-example`, `notification-service` |
| `common` (root `pom.xml`) | reactor root | one `<module>` entry | every library |
| `jacoco-aggregate` | aggregate | one dependency entry | none (excluded from dependents by design) |
| `test-support` | library | README pointer only, both locales | 16 (unchanged behaviour) |

New in-repo dependency edges: `test-support-local` -> `test-support` (compile) and
`test-support-local` -> `rest-client-spring-boot-starter` (compile, optional).

**Gate.** `ludwig-bom` and `ludwig-service-parent` change, so there is no narrower gate than the
whole reactor: `scripts/gate.sh --full`.

**Third-party.** No new third-party dependency and no version change: WireMock, Testcontainers,
ArchUnit and spring-kafka are already managed by `ludwig-bom`.

**Documentation.** Module `README.md` and `README.ru.md`, `CHANGELOG.md` and `CHANGELOG.ru.md`,
the module counts in `CLAUDE.md` and `openspec/config.yaml`, `docs/harness-enforcement.md`, and
the regenerated `PROJECT_INDEX.md` / `project-index.json`.
