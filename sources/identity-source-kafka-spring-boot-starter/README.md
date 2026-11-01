# identity-source-kafka-spring-boot-starter

***English** · [Русский](README.ru.md)*

Feeds [`identity-projection-spring-boot-starter`](../identity-projection-spring-boot-starter) from providers that
publish user changes to Kafka: one subscription per declared source, the published `IdentityUserEvent` v1 contract,
and the platform's consumer from [`messaging-spring-boot-starter`](../messaging-spring-boot-starter) - retry with
growing gaps, then a dead-letter topic.

| | |
|---|---|
| Role | starter |
| Depends on (in-repo) | [`identity-projection-spring-boot-starter`](../identity-projection-spring-boot-starter), [`messaging-spring-boot-starter`](../messaging-spring-boot-starter) |
| Surfaces | Kafka (inbound user streams) |

## Usage

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>identity-source-kafka-spring-boot-starter</artifactId>
</dependency>
```

```yaml
ludwig:
  identity:
    sources:
      staff: {}
      customers: {}
    kafka:
      group-id: orders-identity              # required: every service consumes with its own group
      subscriptions:
        staff:
          topic: corp.staff.users
        customers:
          topic: shop.customer.users
```

Each subscribed topic feeds its source; the source must be declared under `ludwig.identity.sources`, where its role
mapping lives. Without a subscription the starter registers nothing.

### The event contract

```json
{
  "eventId":   "5f1c...",
  "type":      "UPSERT",
  "subject":   "0b7c1b5e-...",
  "displayName": "Anna Schmidt",
  "tenantId":  "tenant-a",
  "roles":     ["CATALOG_ADMIN", "ORDER_AGENT"],
  "email":     "anna.schmidt@example.com",
  "emailVerified": true,
  "alternateEmail": "anna@personal.example",
  "phoneNumber": "+49301234567",
  "phoneVerified": true,
  "chatHandle": "@anna",
  "sourceVersion": "7",
  "occurredAt": "2026-03-01T10:00:00Z"
}
```

- `type` is `UPSERT`, `DISABLE` or `DELETE` (applied as a remove: a status, never a deleted row); `type` and
  `occurredAt` are required.
- **`roles` is the complete set, never a delta**; an event that omits it, or any profile field, keeps the stored
  value.
- **`occurredAt` is the ordering key**: an event not newer than the stored state is skipped, so a replay never
  restores a revoked role.
- **Unknown fields are ignored**: the provider owns the schema.

### Failure handling

| Failure | Handling |
|---|---|
| Not JSON, not an object of the contract, or no `type` / `occurredAt` | Dead-lettered on the first delivery (non-retryable); the partition moves on |
| Applying fails (database down, two replicas inserting one new user) | Retried with growing gaps, then dead-lettered; applying is idempotent, so redelivery is safe |

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `ludwig.identity.kafka.enabled` | `true` | Consume at all (off: the projection's tables are still read) |
| `ludwig.identity.kafka.group-id` | - | **Required** with any subscription: one group per service |
| `ludwig.identity.kafka.subscriptions.<source>.topic` | - | The topic feeding `<source>`; one topic feeds one source |

The listener's consumer settings (retry, dead letter, cluster) are under
`ludwig.messaging.consumers.identity-projection.*` (see [messaging](../messaging-spring-boot-starter)).

## Deployment

- **Topics**: each subscribed topic and its dead-letter topic **`<topic>.dlt`** must exist.
- **Consumer group**: a distinct `group-id` per service; a shared group would give each service only part of the
  stream.

## Observability

Consumption metrics come from [messaging](../messaging-spring-boot-starter#observability)
(`ludwig.messaging.consumed`, `.retries`, `.dead.lettered`, consumer lag, `silent`); what the projection did with each
event is `ludwig_identity_changes_total` (see the projection's README).

**Logs.** `ERROR Dead-lettering an unparseable identity event on <topic>`. Payloads are never logged (directory data
about a person).

**Recommended alerts:** messaging's dead-letter alert on each identity topic; `ludwig_messaging_silent` for the
identity consumer, with a `silence-threshold` matching the provider's change rate.

## Runbook

| Symptom | Likely cause | What to do |
|---|---|---|
| New user has no roles | Event not consumed yet, consumer stuck, or wrong topic/group | Check lag and `silent` for `identity-projection`; `GET /admin/v1/identity/sources` shows the subscription |
| Records on `<topic>.dlt` | Unparseable event, or applying failed repeatedly | For unparseable: ask the provider to republish; for apply failures: fix the cause and replay the dead letters (applying is idempotent) |
| Startup fails naming `group-id` | A subscription without a consumer group | Set `ludwig.identity.kafka.group-id` |
| Startup fails naming a source | The subscribed source is not declared | Declare it under `ludwig.identity.sources` |

## Testing

`IdentityUserEventListenerTest` (topic to source, the contract, non-retryable conversion failures, retried apply
failures), `IdentityKafkaSubscriptionsTest`, `IdentityKafkaAutoConfigurationTest` (the container factory and one feed
per subscription; nothing without a subscription or with `enabled=false`), `IdentityKafkaOrderingTest`.

## Design decisions

- **A separate starter**: the projection is provider-neutral; Kafka and its dependencies reach only the services that
  subscribe.
- **One listener, the topic selects the source**: adding a provider is a configuration line, not a bean.
- **Text payloads parsed in-module**: a contract violation is a conversion failure the platform dead-letters at once,
  distinct from an apply failure that is retried.
- **A mandatory group**: a defaulted group shared by accident is the failure that loses events silently.
