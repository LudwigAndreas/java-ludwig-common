## Why

A UI in front of several services has nowhere suitable to read "what is answering me" from. The data
exists since 2.0.0 - `BuildIdentity` and `ServiceIdentity` in `observability-spring-boot-starter` -
but the only HTTP route to it is `/actuator/info`: Spring Boot's shape rather than a platform
contract, served on the management port a browser is usually not routed to, and carrying the branch
name and dirty flag, which are operator facts and not something to put in a page footer.

## What Changes

- `observability-spring-boot-starter` serves `GET /server/info` on the application port: a small,
  curated JSON document with the service name, version, environment, abbreviated commit and build
  date. Every servlet service that has the starter gets it with no code of its own.
- The document is a fixed allow-list. Branch, dirty flag, CI build number, full commit id, namespace
  and instance are never in it, so it is safe for a service to serve it anonymously.
- A field that cannot be resolved is omitted, never written as a placeholder - the rule
  `build-identity` already sets for the same values.
- One switch, `ludwig.observability.server.info.enabled` (default `true`). The path is fixed and not
  configurable: a UI calling N services needs one well-known path, and a gateway owns any prefix.
- `notification-service` adds `/server/info` to its `ludwig.security.public-paths`, and its
  committed OpenAPI document gains the path.
- `crud-service-example` gains a dependency on `observability-spring-boot-starter`, which it does
  not have today: every starter it uses declares that dependency `optional`, so the reference
  service has been running without it. It then declares `/server/info` public and its committed
  OpenAPI document gains the path.
- That dependency brings the rest of the starter with it, and this is the larger half of the change
  for that service: structured JSON console logs outside the `local` profile, service and commit on
  every log event, the startup identity event, the correlation id filter and its propagation over
  outbound HTTP and Kafka, tracing at the starter's default sampling, and the observability
  integrations of `rest-client-spring-boot-starter`, `export-spring-boot-starter` and
  `file-action-spring-boot-starter`, which are present but dormant until the starter is on the
  classpath. The service's own actuator exposure list is explicit and is not affected.
- `rest-client-spring-boot-starter` is fixed so that a service can use it together with
  `observability-spring-boot-starter` at all. Today the two cannot start in one application: the
  rest-client starter registers its no-identity call context fallback and then, in a later
  auto-configuration, the platform call context as well, and its client builder refuses two. No
  in-repo service combined the two starters before and no test booted both, so the defect was
  found by adding the dependency to `crud-service-example`. It blocks that service and would block
  any consumer making the same combination.
- READMEs (both locales) of `observability-spring-boot-starter` document the endpoint, what it
  deliberately withholds, and that making it anonymous is the service's own `public-paths` entry.
  READMEs (both locales) of `crud-service-example` state that it now includes the starter.

Nothing is **BREAKING** for a consumer of the platform: the starter gains a bean, a property and an
endpoint. For `crud-service-example` itself the log format outside `local` changes from text to
JSON, which matters to anyone parsing that service's output.

## Capabilities

### New Capabilities

- `server-info`: the curated, UI-facing description of a running service - its path, the exact set
  of fields it may carry, how absent values are represented, and how it is switched off.

- `outbound-call-context`: which ambient identity an outbound call made through the rest-client
  starter carries - the platform's when observability is present, none when it is not, the
  application's own when it declares one - and that exactly one source is ever in effect.

### Modified Capabilities

None. Shared contracts in `openspec/specs/` touched: **none are changed**. Three are relied on as
written: `build-identity` (the endpoint reads the resolved identity and never the repository, and
adds nothing to the telemetry grouping identity), `published-api-document` (a new path fails each
service's build until its committed document is refreshed, which is that contract working, not a
change to it) and `service-log-stream` (it describes the one log stream every service writes;
`crud-service-example` starts writing it once the owning starter is on its classpath).

## Impact

| Module | POM tier | Change | In-repo dependents (the gate runs each) |
|---|---|---|---|
| `observability-spring-boot-starter` | library (parent `common`) | controller, response record, property, auto-configuration bean, tests, READMEs | `export-spring-boot-starter`, `file-action-spring-boot-starter`, `notification-service`, `reconciliation-spring-boot-starter`, `rest-client-spring-boot-starter`, `user-settings-spring-boot-starter` |
| `notification-service` | service (parent `ludwig-service-parent`) | `public-paths` entry, integration test, refreshed `docs/api/notification-service.openapi.yaml` | none |
| `crud-service-example` | service (parent `ludwig-service-parent`) | new dependency on `observability-spring-boot-starter`, `public-paths` block, integration test, refreshed `docs/api/crud-service-example.openapi.yaml`, READMEs | none |

| `rest-client-spring-boot-starter` | library (parent `common`) | auto-configuration ordering and one condition, regression test | `crud-service-example`, `export-spring-boot-starter`, `reconciliation-spring-boot-starter` |

- One new in-repo dependency edge: `crud-service-example` → `observability-spring-boot-starter`.
  After it, `crud-service-example` joins that starter's in-repo dependents, and `PROJECT_INDEX.md`
  and `project-index.json` are regenerated.
- None of the root POM, `ludwig-bom` or `ludwig-service-parent` changes. The starter's own POM
  declares `jackson-annotations` as an optional dependency, at the version
  `spring-boot-dependencies` already manages; the service's POM declares the starter, at the version
  `ludwig-bom` already manages.
- No database, messaging, audit, cache or i18n impact: the endpoint is read-only, raises no problem
  of its own and carries no user-facing text.
- `CHANGELOG.md` and `CHANGELOG.ru.md` gain an `Added` entry under `[Unreleased]` at archive.

## Non-goals

- No tuning of what the starter switches on in `crud-service-example`. The service takes the
  starter's defaults; sampling rates, masked keys and excluded paths are left for a change that has a
  reason to move them.
- No aggregation across services, no service registry, no gateway. The UI calls each service it
  already talks to.
- No change to `security-spring-boot-starter`'s default `public-paths`. Anonymous access is declared
  by each service.
- No change to `/actuator/info`, to `BuildIdentity` or to `ServiceIdentity`.
- No response header carrying the same identity on every response.
- No UI. This repository ships no frontend, and the UI's own build version is not this endpoint's
  business.
