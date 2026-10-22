## Why

A service's HTTP surface is a generated, committed and drift-checked OpenAPI document. Its Kafka
surface has nothing of the kind. Other teams consume its topics and publish to the topics it reads, and
the only description of what goes over the wire is the code. Two other gaps:

- Neither kind of document reaches those teams. `docs/api/` exists only inside this repository.
- On the producer side, the outbox knows which event type goes to which topic and cluster, but not what
  the payload is, because `OutboxEvent.payload` is `Object`. No generator can describe it without a
  declaration.

## What Changes

- **An AsyncAPI document per service, generated and drift-checked like the OpenAPI one.**
  `docs/api/<artifactId>.asyncapi.yaml` is produced by Springwolf from the service's real application
  context, canonicalized, committed, and compared during `verify`. A hand edit fails exactly as drift
  does.
- **New starter `asyncapi-spring-boot-starter`** brings Springwolf and adds what Springwolf cannot know:
  - the platform envelope headers on every message
  - each consumer's cluster and dead-letter channel
  - one send operation per outbox event type, with its topic and cluster taken from the outbox routes
  - Kafka clusters as AsyncAPI servers, named, with the name in place of an address
- **The outbox declares a payload type per event type.** `OutboxEventTypes`, a registry bean, maps
  `eventType → payload class`. With `ludwig.outbox.events.require-declared=true` (meant for tests), the
  publisher refuses an undeclared event type and a payload of the wrong class, so a producer cannot go
  undocumented unnoticed.
- **One shared document check.** The fetch, canonicalize and compare logic that both services'
  `ApiDocumentIT` copy today moves to `test-support`, and both document kinds use it.
- **Publishing to Nexus.** `scripts/api-docs.sh refresh | check | publish`:
  - `refresh` regenerates every committed document, OpenAPI and AsyncAPI
  - `check` runs the drift comparison for all of them
  - `publish --version <v>` uploads each document to the platform's Nexus repositories as
    `ru.ludwigandreas:<artifactId>:<v>:yaml:openapi` and `…:yaml:asyncapi`, so other teams fetch a
    versioned document from where they already fetch artifacts

## Non-goals

- Compatibility checking. The check is drift only, as for OpenAPI. A breaking payload change passes the
  build and is visible in the diff. Checking schema changes against `ludwig-event-version` is a later
  change.
- Contract-first generation of payload classes from AsyncAPI.
- Documenting other teams' payload schemas as ours. A topic this service only consumes is documented as
  a receive operation describing the shape it expects. Nothing in the document claims to own it.
- Publishing as part of `mvn deploy`. Services do not deploy today, and this change does not make them.
- A browsable UI in production. Springwolf's UI is not added.

## Capabilities

### New Capabilities

- `published-async-document`: a service's Kafka surface as a generated, committed, drift-checked
  AsyncAPI document carrying the platform envelope, clusters, consumers and outbox producers.

### Modified Capabilities

- `published-api-document`: the documents are published to Nexus by `scripts/api-docs.sh`, and one
  script refreshes and checks both kinds. The existing requirements are unchanged.

Shared contracts in `openspec/specs/` touched: `published-api-document` (one added requirement).
`messaging-envelope`, whose header set is what the envelope customizer documents, is read and not
modified.

## Impact

| Module | POM tier | Change | In-repo dependents (gate) |
|---|---|---|---|
| `asyncapi-spring-boot-starter` | library (parent `common`) | **new** | none |
| `messaging-spring-boot-starter` | library | `ListenerContainerFactoryBuilder.consumerNameOf(factory)` | identity-projection, messaging-db, notification-service, outbox, user-settings |
| `outbox-spring-boot-starter` | library | `OutboxEventTypes` and the `require-declared` check | audit, crud-service-example, export, file-action, file-ingest, messaging-db, notification-service, user-settings |
| `audit-spring-boot-starter`, `export-spring-boot-starter`, `file-ingest-spring-boot-starter`, `user-settings-spring-boot-starter` | library | declare the event types they publish through the outbox | per manifest |
| `test-support` | library (role `test-support`) | shared published-document check | its 20 test-scope dependents |
| `crud-service-example` | service (parent `ludwig-service-parent`) | uses the starter; declares its event types; `AsyncApiDocumentIT`; `ApiDocumentIT` on the shared check | none |
| `notification-service` | service | the same | none |
| `ludwig-bom` | bom | Springwolf version; the new artifact | every module |
| root `pom.xml` | parent | `<module>` | — |

New third-party dependency: Springwolf (`springwolf-kafka`), in `asyncapi-spring-boot-starter` only.
Its version is pinned in `ludwig-bom` after a compatibility spike against Spring Boot 3.3.5 and
spring-kafka 3.2.4.
