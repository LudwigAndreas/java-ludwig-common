## Why

`test-support-local` exists but nothing uses it, so its API is designed against a sample with one
rest client and nobody can copy a working setup. `notification-service` is the harder of the two
reference services to run on a laptop - it sends mail, calls a chat API and arbitrary webhooks,
consumes Kafka, and validates JWTs - which makes it the right place for the reference example, and
the place where the module's first design gaps show: none of its three outbound partners is a
`@LudwigRestClient`, so today's stub contract cannot stand in for any of them.

## What Changes

- **`notification-service` gets a local application.** `LocalNotificationApplication`, in
  `src/test/java/ru/ludwigandreas/notification/local`, starts `NotificationApplication::main` with
  PostgreSQL, Kafka, a local mail server, stand-ins for the chat API, a webhook receiver and the
  identity provider, and seed data. Every channel and both ingresses are **on**; nothing is
  switched off to make the service start.
- **Calling it is one pasted command.** The identity-provider stand-in serves real discovery and
  signing keys, so the service validates tokens through its production decoder. The start-up
  summary prints a ready `curl` with a token for each seeded caller.
- **Seed data a developer can act on at once**: recipients with every contact shape (email only,
  chat, webhook, opted out, quiet hours, deactivated), one notification already delivered and one
  queued, a published announcement, and one request on the Kafka ingress topic.
- **The same stand-ins and fixtures serve the integration tests.** New tests cover a chat outage, a
  slow webhook and a redelivered Kafka request through `@WithPartnerStubs` and `KafkaDeliveries`.
  One test launches the local application itself, so the reference example cannot rot.
- **`test-support-local` grows what the adoption needs** (its API is marked provisional for
  exactly this):
  - a stub can stand in for a partner addressed by **any** property, or by none (a sink whose
    address the fixtures hand out), not only for a rest-client name;
  - `LocalRun.mail()` starts an in-process mail server and publishes `spring.mail.*`;
  - a stub and the launcher can contribute lines to the start-up summary.
- **The rule for a service with no rest clients.** `notification-service` subclasses
  `LocalRunRulesTest`; a service-local ArchUnit rule fails the build when a new class builds an
  outbound `RestClient` outside the one configuration class whose partners the catalog covers.
- The service README (both locales) gains "Running locally" as the reference walk-through, and
  `test-support-local`'s README points at it.

## Non-goals

- **No production code change in `notification-service`.** Nothing under its `src/main` is edited:
  the chat and webhook channels keep their own `RestClient` and their own retry and backoff. Moving
  them to `@LudwigRestClient` would change delivery semantics and is a separate decision.
- **No header-based authentication backdoor**, in main or test sources. The local run uses real
  tokens from a stand-in issuer.
- **No local application for `crud-service-example`.** The identity-provider stand-in stays in
  `notification-service`'s test sources; promoting it to a shared module waits for the second user.
- **No rewrite of the existing integration tests.** `NotificationTestBase` and the tests built on it
  stay as they are; new tests show the new style beside them.
- **No clock seam, no deterministic IDs, no broker or database fault injection** - unchanged from
  the previous change's non-goals.
- **No fix for the two findings recorded there** (`rest-client-spring-boot-starter`'s
  not-really-optional dependencies; `PostgresContainerConfiguration` stopping the shared
  container). Neither blocks this change.

## Capabilities

### New Capabilities

- `notification-local-run`: what `notification-service`'s local application starts, seeds and
  prints, and the checks that keep it working.

### Modified Capabilities

- `partner-stub`: adds stubs addressed by an arbitrary property or by none, and summary lines.
- `local-run`: adds the in-process mail server as a nameable backing service.

Both were introduced by the change `add-ludwig-test-fixtures`, which is **not archived yet** (its
gate is blocked on the unreachable releases repository), so neither exists under `openspec/specs/`
today. This change's deltas are written as additions only. **Archive `add-ludwig-test-fixtures`
first**; its requirement "A stub is an HTTP server for one rest-client name" is narrowed by this
change and has to be reworded then.

## Impact

**Shared contracts in `openspec/specs/`.** None is changed. Read and complied with:
`module-dependency-direction` (direction check in the design), `enforcement-triad` (the new rule
is ArchUnit, local to the service, as the in-app and announcement rules are), `test-layout`,
`container-image-pinning` (no new image: the mail server and every stand-in are in-process),
`in-app-notification`, `notification-inbox`, `platform-announcement` and
`announcement-email-broadcast` (seed data goes through the services those specify).

**Modules touched, tier, and in-repo dependents** (from `scripts/manifest.sh module`):

| Module | Tier | What changes | In-repo dependents |
|---|---|---|---|
| `test-support-local` | library, parent `common`, imports `ludwig-bom` | stub addressing, `LocalRun.mail()`, summary contributions, README | none today; `notification-service` after this change |
| `notification-service` | service, parent `ludwig-service-parent` | test sources, test resources, test-scoped POM dependencies, README | none |

New in-repo dependency edge: `notification-service` -> `test-support-local` (test).

**Gate.** `scripts/gate.sh --change adopt-local-run-in-notification-service test-support-local
notification-service`. No BOM or parent POM changes, so the gate is not the whole reactor. It
includes `scripts/check_api_baseline.sh`, which cannot pass on a machine that cannot reach the
releases repository.

**Third-party.** No new version. `notification-service` declares, at test scope and without
versions, `wiremock-standalone` and `archunit` (managed by `ludwig-bom`); `greenmail-junit5`,
`org.testcontainers:kafka` and `spring-security-oauth2-jose` are already on its test classpath.

**API.** `test-support-local`'s provisional API changes: `PartnerStub.clientName()` is renamed and
its meaning widened. Nothing else consumes it.
