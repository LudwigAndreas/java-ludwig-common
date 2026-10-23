# asyncapi-spring-boot-starter

***English** · [Русский](README.ru.md)*

Generates a service's Kafka surface as an AsyncAPI 3.0 document from its real application context.
[Springwolf](https://www.springwolf.dev) scans the `@KafkaListener` methods; this module adds what
Springwolf cannot know - clusters, dead-letter channels, the platform envelope headers, and the events
the service produces through the outbox. The document is committed under `docs/api/` and checked for
drift like the OpenAPI document (see [`docs/api/README.md`](../../docs/api/README.md)).

| | |
|---|---|
| Role | starter (build-time documentation) |
| Depends on (in-repo) | [`messaging-spring-boot-starter`](../messaging-spring-boot-starter), [`outbox-spring-boot-starter`](../outbox-spring-boot-starter) |
| Used by | `crud-service-example`, `notification-service` |
| Surfaces | none of its own (Springwolf's document endpoint) |

## Usage

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>asyncapi-spring-boot-starter</artifactId>
</dependency>
```

1. Declare what the service produces (below).
2. Add an `AsyncApiDocumentIT` beside the service's `ApiDocumentIT`:

   ```java
   @Test
   void committedDocumentIsCurrent() throws Exception {
       PublishedDocument.check(ARTIFACT_ID, PublishedDocument.Kind.ASYNCAPI,
               objectMapper.readTree(serializer.toJsonString(asyncApi.getAsyncAPI())), AsyncApiDocumentIT.class);
   }
   ```

3. The first run fails and writes `target/asyncapi/<artifactId>.asyncapi.yaml`. Run
   `scripts/api-docs.sh refresh` and commit the document.

### What the document contains

| Part | Source |
|---|---|
| A receive operation per listener, with its payload schema | Springwolf's scan. A `ConsumerRecord<String, T>` listener documents `T`; a text listener documents a string |
| The cluster each consumer's channel is bound to | `ludwig.messaging.consumers.<name>.cluster` |
| A channel per consumer's dead-letter topic, on the dead-letter cluster | `dead-letter.cluster`, `dead-letter.suffix` |
| A send operation per declared outbox event type routed over Kafka | `OutboxEventTypes` and `ludwig.outbox.routes.*` |
| The platform envelope as every message's headers | `MessageHeaders`, plus the configured correlation header |
| One server per Kafka cluster, named; its host is the cluster name, not an address | `default` and every `ludwig.kafka.clusters` key |

A topic the service only consumes is documented as a receive operation describing the shape it expects;
the document does not claim to own it. Events routed over REST are not in the document.

### Declaring what the outbox produces

An outbox payload is `Object`, so the service declares each event type once:

```java
@Bean
OutboxEventTypes catalogEvents() {
    return OutboxEventTypes.of(Map.of("ProductCreated", ProductEventPayload.class));
}
```

Topic and cluster come from the outbox routes. An event published down a route by name with
`OutboxEvent.route(...)` declares the route, because that choice is made in code:

```java
OutboxEventTypes.routed("NotifyProductCreated", ProductEventPayload.class, "product-notifications")
```

Set `ludwig.outbox.events.require-declared=true` in the service's **test** properties: the publisher then
refuses undeclared types, wrong payload classes and undeclared routes, so an undocumented producer fails a
test. This only covers publishes that some test exercises - the event type is a runtime string, so no
static check is possible.

## Configuration

The module sets Springwolf's required defaults at the lowest precedence; the service can override any
of them.

| Property | Default | Why |
|---|---|---|
| `springwolf.docket.servers.<cluster>.protocol` / `.host` | `kafka` / the cluster name, one per cluster | The committed document names clusters, not addresses |
| `springwolf.docket.base-package` | `ru.ludwigandreas` | Listeners contributed by platform starters are part of the service's surface |
| `springwolf.docket.info.title` | `spring.application.name` | |
| `springwolf.docket.info.version` | `current` | The Nexus coordinate carries the version; a version inside the document would make every release a drift |
| `springwolf.payload.extractable-classes[org.apache.kafka.clients.consumer.ConsumerRecord]` | `1` | Without it a `ConsumerRecord<String, T>` listener is documented as an empty object |
| `springdoc.packages-to-exclude` | `io.github.springwolf` | Keeps Springwolf's endpoints out of the OpenAPI document. A service that sets this property must include the package |
| `springwolf.use-fqn` | `false` | `true` leaks fully-qualified schema names into the OpenAPI document (Springwolf sets a JVM-wide swagger flag while scanning) |

Springwolf is pinned to 1.9.0 in `ludwig-bom`, the last release built against Spring Boot 3.3.x. Upgrade
it together with Spring Boot.

## Deployment

Nothing to deploy. The committed document is published to Nexus by the pipeline; see
[`docs/api/README.md`](../../docs/api/README.md#publishing-to-nexus).

## Observability

Not applicable: the module only builds a document at startup and adds no runtime behaviour to message
handling.

## Audit

Not applicable: no state changes.

## Runbook

| Symptom | Likely cause | What to do |
|---|---|---|
| `AsyncApiDocumentIT` fails with a diff | The service's Kafka surface changed (new listener, route, payload field) | Read the diff. If intended: `scripts/api-docs.sh refresh`, commit the document with the change |
| A listener's payload documented as an empty object | `extractable-classes` override removed | Restore the default |
| An outbox event missing from the document | Not declared in `OutboxEventTypes`, or routed over REST | Declare it; REST routes are out of scope |
| Springwolf endpoints appear in the OpenAPI document | Service overrode `springdoc.packages-to-exclude` without `io.github.springwolf` | Add the package |

## Testing

`AsyncApiDocumentShapeIT` builds a document from a context with listeners, clusters, routes and declared
events (no broker, no Docker) and checks its shape, including that a declared event without a Kafka route
is absent. Each consuming service owns an `AsyncApiDocumentIT`.

## Design decisions

- **Drift check, not compatibility check.** A breaking payload change passes the build and shows up as a
  diff for review.
- **No Springwolf UI.** Consumers read the published document from Nexus.
- **No `@AsyncPublisher` annotations.** Outbox routes plus `OutboxEventTypes` are the one place a producer
  is described, so topic and cluster cannot disagree between an annotation and the routing.
