# asyncapi-spring-boot-starter

[Русская версия](README.ru.md)

A service's Kafka surface as an AsyncAPI 3.0 document, generated from its real application context.

[Springwolf](https://www.springwolf.dev) scans the `@KafkaListener` methods. This module adds what
Springwolf cannot know, so that the document describes what actually goes over the wire on this platform.
The document is committed under `docs/api/` and checked for drift exactly like the OpenAPI document. See
[`docs/api/README.md`](../../docs/api/README.md) for the check, the refresh command and publishing to
Nexus.

- [What the document contains](#what-the-document-contains)
- [Adopting it](#adopting-it)
- [Declaring what the outbox produces](#declaring-what-the-outbox-produces)
- [Defaults, and how to override them](#defaults-and-how-to-override-them)
- [What this module deliberately does not do](#what-this-module-deliberately-does-not-do)

## What the document contains

| Part | From |
|---|---|
| A receive operation per listener, with its payload schema | Springwolf's scan. A `ConsumerRecord<String, T>` listener documents `T`; a text listener documents a string |
| The cluster each consumer's channel is bound to | `ludwig.messaging.consumers.<name>.cluster` |
| A channel for each consumer's dead-letter topic, on the dead-letter cluster | `dead-letter.cluster` and `dead-letter.suffix` |
| A send operation per declared outbox event type routed over Kafka | `OutboxEventTypes` and `ludwig.outbox.routes.*`, see below |
| The platform envelope as every message's headers | `MessageHeaders`, plus the configured correlation header |
| One server per Kafka cluster, named, its host the name rather than an address | `default` and every `ludwig.kafka.clusters` key |

A topic this service only consumes is documented as a receive operation describing the shape it expects.
Nothing in the document claims to own it.

## Adopting it

Add the dependency:

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>asyncapi-spring-boot-starter</artifactId>
</dependency>
```

Then declare what the service produces (below), and add an `AsyncApiDocumentIT` beside the service's
`ApiDocumentIT`:

```java
@Test
void committedDocumentIsCurrent() throws Exception {
    PublishedDocument.check(ARTIFACT_ID, PublishedDocument.Kind.ASYNCAPI,
            objectMapper.readTree(serializer.toJsonString(asyncApi.getAsyncAPI())), AsyncApiDocumentIT.class);
}
```

The first run fails and writes `target/asyncapi/<artifactId>.asyncapi.yaml`. Refresh with
`scripts/api-docs.sh refresh` and commit the document.

## Declaring what the outbox produces

An outbox event's payload is `Object`, so nothing can describe it until the service says what it is. Do
it once per event type:

```java
@Bean
OutboxEventTypes catalogEvents() {
    return OutboxEventTypes.of(Map.of("ProductCreated", ProductEventPayload.class));
}
```

The topic and cluster are not repeated: the outbox routes already say them. An event the code publishes
down a route by name, with `OutboxEvent.route(...)`, says which route, because that choice is made in code
and no configuration records it:

```java
OutboxEventTypes.routed("NotifyProductCreated", ProductEventPayload.class, "product-notifications")
```

An event type routed over REST is not part of this document.

Set `ludwig.outbox.events.require-declared=true` in the service's test properties. The publisher then
refuses an undeclared event type, a payload of the wrong class, or a route other than the declared one, so
a producer cannot go undocumented
without a test failing. It stays off in production. No static check is possible here, because the event
type is a runtime string. That makes the tests the check, which only works if a test exercises each
publish.

## Defaults, and how to override them

Springwolf will not build a document without servers, a base package and an info block. This module sets
them at the lowest precedence, so any of them can be overridden in the service's configuration:

| Property | Default | Why |
|---|---|---|
| `springwolf.docket.servers.<cluster>.*` | one per cluster, protocol `kafka`, host = the cluster name | the committed document replaces each host with the server's name (AsyncAPI requires a host); the names are what a reader needs |
| `springwolf.docket.base-package` | `ru.ludwigandreas` | listeners contributed by platform starters are part of the service's surface |
| `springwolf.docket.info.title` | `spring.application.name` | |
| `springwolf.docket.info.version` | `current` | the Nexus coordinate is the real version; one inside the document would make every release a drift |
| `springwolf.payload.extractable-classes[org.apache.kafka.clients.consumer.ConsumerRecord]` | `1` | without it, a `ConsumerRecord<String, T>` listener is documented as an empty object |
| `springdoc.packages-to-exclude` | `io.github.springwolf` | Springwolf's own endpoints stay out of the service's OpenAPI document; a service that sets this property must include the package |
| `springwolf.use-fqn` | `false` | Springwolf sets swagger's JVM-wide type-name flag to this while it scans; `true` leaks fully-qualified schema names into the OpenAPI document |

Springwolf is pinned to 1.9.0 in `ludwig-bom`, the last release built against Spring Boot 3.3.x. Move it
together with the Boot version.

## What this module deliberately does not do

- **Check compatibility.** The check is drift only. A breaking payload change passes the build and is
  visible in the diff.
- **Ship Springwolf's UI.** Other teams read the published document from Nexus.
- **Document `@AsyncPublisher` annotations.** The outbox routes and `OutboxEventTypes` are the single
  place a producer is described, so topic and cluster cannot drift between an annotation and the routing.
