# outbox-spring-boot-starter

***English** · [Русский](README.ru.md)*

Transactional outbox for Spring Boot services on PostgreSQL. A service writes an event into the
`outbox_message` table in the same transaction as the business change it describes; a background
poller delivers it to Kafka, to a REST endpoint or to a custom transport. No event is sent for a change
that rolled back, and no committed change loses its event.

| | |
|---|---|
| Role | starter |
| Depends on (in-repo) | [`audit-core`](../audit-core), [`db-core`](../db-core), [`job-core`](../job-core), [`messaging-spring-boot-starter`](../messaging-spring-boot-starter) |
| Used by | `asyncapi`, `audit`, `export`, `file-action`, `file-ingest`, `user-settings` starters; `crud-service-example`, `notification-service` |
| Surfaces | Kafka and REST (outbound only) |

## Usage

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>outbox-spring-boot-starter</artifactId>
</dependency>
```

Publish from inside the transaction that makes the change:

```java
@Service
class OrderService {

    private final OutboxEventPublisher outboxEventPublisher;

    @Transactional
    public void createOrder(Order order) {
        orderRepository.save(order);

        outboxEventPublisher.publish(OutboxEvent.builder()
                .aggregateType("Order")
                .aggregateId(order.getId().toString())
                .eventType("OrderCreated")
                .payload(new OrderCreatedPayload(order.getId(), order.getTotal()))
                .build());
    }
}
```

`publish` requires an ambient transaction (`Propagation.MANDATORY`). Called outside one it throws,
because without the transaction the atomicity guarantee does not exist.

### What happens after `publish`

A poller claims due rows with `SELECT ... FOR UPDATE SKIP LOCKED`, so any number of instances poll
concurrently without processing a row twice. Each claimed message is dispatched over the transport its
route resolves to, and the outcome is recorded:

```
PENDING ──claim──> PROCESSING ──success──> PUBLISHED
                       │
                       ├──failure, attempts left──> PENDING (next_attempt_at = now + backoff)
                       └──failure, budget spent───> DEAD_LETTER   (FAILED if dead-letter disabled)
```

An administrator can retry a `FAILED` or `DEAD_LETTER` message (back to `PENDING`, attempts reset) or discard a
`PENDING`, `FAILED` or `DEAD_LETTER` one to `DISCARDED`, which is terminal and never claimed - see
[Administrative endpoints](#administrative-endpoints). `DISCARDED` needed no migration: `status` is a `VARCHAR(20)`
with no check constraint, and the poll index's predicate (`status IN ('PENDING', 'FAILED')`) already excludes it.
Discarded rows are kept and purged by the same retention as dead-lettered ones (see [Deployment](#deployment)).

A `PROCESSING` row whose owner died is returned to `PENDING` by the stale-claim reclaimer after
`processing.stale-timeout`. On shutdown the poller finishes the cycle in progress (up to
`processing.drain-timeout`) instead of abandoning its claimed rows.

### Routing

```properties
ludwig.outbox.routes.order-events.transport=KAFKA
ludwig.outbox.routes.order-events.destination=orders-topic
ludwig.outbox.routes.order-events.event-types=OrderCreated,OrderCancelled

ludwig.outbox.routes.audit-events.transport=REST
ludwig.outbox.routes.audit-events.destination=https://audit.example.com/events

ludwig.outbox.default-route.transport=KAFKA
ludwig.outbox.default-route.destination=default-topic
```

Resolution order:

1. `OutboxEvent.route()` - an explicit route name set by the caller;
2. the first `ludwig.outbox.routes.*` entry whose `event-types` contains the event type;
3. `ludwig.outbox.default-route`.

**One Kafka transport per cluster.** `KAFKA` writes through `spring.kafka.*`. Every cluster declared
under `ludwig.kafka.clusters` (see [messaging](../messaging-spring-boot-starter#kafka-clusters)) adds a
transport `KAFKA:<name>`:

```properties
ludwig.outbox.routes.partner-invoices.transport=kafka:partner-a
ludwig.outbox.routes.partner-invoices.destination=invoices
```

A route naming an undeclared cluster fails startup. To add a transport beyond Kafka and REST, register
an `OutboxDispatcher` bean (and an `OutboxRouteResolver` if routing needs to change).

### Message envelope

Kafka records carry the platform envelope headers from `messaging`'s `MessageHeaders`: event type and
version, aggregate type and id, idempotency key, trace id, produced-at, and every header on the message.
The record key is the ordering key when set, otherwise the aggregate id, so events of one aggregate land on one partition.

The request's correlation id is captured into the message headers at `publish` time under
`ludwig.messaging.correlation-header` (default `X-Correlation-Id`), unless the event already sets it.
The poller runs on its own thread with no request context, so `publish` is the only moment the id can
be read. The id comes from audit-core's `CorrelationProvider`, which
[`observability-spring-boot-starter`](../observability-spring-boot-starter) provides.

### Declared event types

An `OutboxEventTypes` bean declares which payload class each event type carries. Several beans are
merged:

```java
@Bean
OutboxEventTypes catalogEventTypes() {
    return OutboxEventTypes.merge(List.of(
            OutboxEventTypes.of(Map.of("ProductCreated", ProductEventPayload.class)),
            // An event sent down a route by name declares that route.
            OutboxEventTypes.routed("NotifyProductCreated", ProductEventPayload.class, "product-notifications")));
}
```

- A type declared twice with different classes or routes fails startup.
- [`asyncapi-spring-boot-starter`](../asyncapi-spring-boot-starter) reads the declarations to describe
  each event in the service's AsyncAPI document, on the topic and cluster its route resolves to.
- With `ludwig.outbox.events.require-declared=true`, `publish` refuses an undeclared type, a payload of
  the wrong class and an explicit route other than the declared one. Set it in every service's **test**
  properties, so a producer nobody declared fails that service's own tests. Production keeps the default
  `false`.

### Ordering

Events that share `OutboxEvent.orderingKey(...)` are dispatched in creation order: the poll query claims a
message only when every earlier unresolved message with the same key is `PUBLISHED`. A `DEAD_LETTER`
message no longer blocks its key, so one poison message cannot stall the key forever. Disable with
`ludwig.outbox.ordering.enabled=false`.

### Idempotency

`OutboxEvent.idempotencyKey(...)` makes `publish` return the existing row instead of inserting a
duplicate (unique partial index). Two genuinely concurrent publishes of one key abort the losing
caller's transaction with `DataIntegrityViolationException`; treat that as "already handled".

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `ludwig.outbox.enabled` | `true` | Master switch |
| `ludwig.outbox.polling.enabled` | `true` | Run the poller and the stale-claim reclaimer |
| `ludwig.outbox.polling.fixed-delay` | `5s` | Delay between poll cycles |
| `ludwig.outbox.polling.initial-delay` | `5s` | Delay before the first poll cycle |
| `ludwig.outbox.polling.batch-size` | `100` | Max rows claimed per cycle |
| `ludwig.outbox.polling.lock-owner` | hostname + random suffix | Value written to `locked_by` |
| `ludwig.outbox.processing.stale-timeout` | `5m` | A `PROCESSING` row older than this is reclaimed to `PENDING` |
| `ludwig.outbox.processing.stale-reclaim-fixed-delay` | `1m` | How often the reclaimer runs |
| `ludwig.outbox.processing.drain-timeout` | `20s` | How long shutdown waits for the cycle in progress. Keep below the pod's termination grace period |
| `ludwig.outbox.retry.max-attempts` | `10` | Attempts before dead-lettering |
| `ludwig.outbox.retry.initial-interval` | `1s` | First backoff interval |
| `ludwig.outbox.retry.multiplier` | `2.0` | Backoff growth factor |
| `ludwig.outbox.retry.max-interval` | `5m` | Backoff cap |
| `ludwig.outbox.retry.jitter` | `0.2` | Each interval is randomized by ±20%, so messages that failed together do not all retry at the same instant. `0` = deterministic |
| `ludwig.outbox.ordering.enabled` | `true` | Honour `orderingKey` |
| `ludwig.outbox.idempotency.enabled` | `true` | Honour `idempotencyKey` |
| `ludwig.outbox.dead-letter.enabled` | `true` | Move exhausted messages to `DEAD_LETTER`; otherwise they stay `FAILED` |
| `ludwig.outbox.audit.persist-history` | `false` | Also persist every transition to `outbox_status_history` |
| `ludwig.outbox.retention.enabled` | `true` | Run the retention purge (needs a `RunLock`; without one, a startup WARN and no purge) |
| `ludwig.outbox.retention.published` | `7d` | Keep a `PUBLISHED` message this long after publication; at least `1d` |
| `ludwig.outbox.retention.terminal` | `30d` | Keep a `DEAD_LETTER` or `DISCARDED` message this long after it became terminal |
| `ludwig.outbox.retention.interval` | `1h` | Delay between purge runs |
| `ludwig.outbox.retention.batch-size` | `500` | Messages deleted per committed batch |
| `ludwig.outbox.retention.drain-timeout` | `30s` | How long shutdown waits for an in-flight purge |
| `ludwig.outbox.metrics.enabled` | `true` | Emit Micrometer metrics (when Micrometer is on the classpath) |
| `ludwig.outbox.liquibase.enabled` | `true` | Apply the shipped changelog as a standalone `SpringLiquibase` bean |
| `ludwig.outbox.rest.connect-timeout` / `read-timeout` | `5s` / `10s` | REST dispatcher timeouts |
| `ludwig.outbox.rest.endpoints.<key>` | - | Logical endpoint name → URL, for destinations that are not a full URL |
| `ludwig.outbox.kafka.send-timeout` | `10s` | Max wait for the broker acknowledgement per message |
| `ludwig.outbox.events.require-declared` | `false` | Refuse undeclared event types (see [Declared event types](#declared-event-types)) |
| `ludwig.outbox.routes.<name>.transport` / `.destination` / `.event-types` | - | A named route |
| `ludwig.outbox.default-route.transport` / `.destination` | - | Route for events no named route matches |

Backoff, scheduling and claiming come from [`job-core`](../job-core), shared with
[`reconciliation-spring-boot-starter`](../reconciliation-spring-boot-starter), so every worker in the
platform retries on the same curve.

## Deployment

**Schema (PostgreSQL only).** Liquibase changelog `classpath:db/changelog/outbox/outbox-changelog.xml`
creates `outbox_message` and `outbox_status_history`. Apply it one of two ways:

- include it from the service's own master changelog:
  ```xml
  <include file="classpath:db/changelog/outbox/outbox-changelog.xml" relativeToChangelogFile="false"/>
  ```
  and set `ludwig.outbox.liquibase.enabled=false`;
- or let the starter run it as a second `SpringLiquibase` bean against the primary `DataSource`
  (default, needs `liquibase-core` on the classpath).

Changeset ids are namespaced (`outbox-NNNN`, author `ludwig-outbox`) and never collide with a service's
own changesets.

**Repositories** are registered by the starter for its own packages only; the service configures no
entity or repository scanning for them.

**Kafka/REST.** Topics and endpoints named in routes must exist and be reachable. Kafka credentials come
from `spring.kafka.*` or the named cluster's configuration.

**Scheduling.** The poller runs on a `TaskScheduler` owned by the starter; the service needs no
`@EnableScheduling`.

**Data growth.** The retention purge (`ludwig-outbox-retention-purge`, a `job-core` job under its own run lock)
deletes `PUBLISHED` messages 7 days after publication and `DEAD_LETTER` / `DISCARDED` messages 30 days after they
became terminal, with their `outbox_status_history` rows, in committed batches of 500. `PENDING`, `PROCESSING` and
`FAILED` messages are never deleted, and an administrator's retry takes a message out of retention until it is
terminal again. Every batch is audited as `outbox.purged` (`PARTIAL` while expired messages remain) and counted in
`ludwig.outbox.purged{status}`. It is listed by `GET /admin/v1/jobs` and can be run with `jobs.operate`.

**`retention.published` is the idempotency window.** An idempotency key is deduplicated against rows that still
exist, so once a published row is purged a duplicate carrying its key is accepted and sent again. Set it longer
than any producer can retry; the `1d` minimum is a floor, not a recommendation. Upgrading: rows that were
`DEAD_LETTER` or `DISCARDED` before migration `0006` count their retention from the migration.

## Observability

**Metrics** (Micrometer; Prometheus names in brackets):

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `ludwig.outbox.published` (`ludwig_outbox_published_total`) | counter | `eventType`, `transport` | Events written to the outbox |
| `ludwig.outbox.filtered` | counter | `eventType` | Events an `OutboxPublishFilter` dropped |
| `ludwig.outbox.dispatch.succeeded` | counter | `eventType`, `transport`, `destination` | Successful deliveries |
| `ludwig.outbox.dispatch.failed` | counter | `eventType`, `transport`, `destination` | Failed delivery attempts (including ones that will be retried) |
| `ludwig.outbox.deadlettered` | counter | `eventType`, `transport` | Messages moved to `DEAD_LETTER` |
| `ludwig.outbox.dispatch.duration` | timer | `transport`, `destination` | Duration of one dispatch call |
| `ludwig.outbox.latency` | timer | `eventType`, `transport` | End to end: `created_at` → `PUBLISHED`, including polling delay and backoff. **The metric to put an SLO on** |

**Logs.** `WARN` when a dispatcher throws (message id, cause) and when stale `PROCESSING` rows are
reclaimed (count). Payloads are never logged.

**Traces.** The trace id of the publishing request is stored in `outbox_message.trace_id` and sent as
the `ludwig-trace-id` header, so a consumer can link to the producing trace.

**Health.** No health indicator.

**Recommended alerts:**

| Condition | Why |
|---|---|
| `increase(ludwig_outbox_deadlettered_total[15m]) > 0` | A downstream system will never receive these events without intervention |
| `histogram_quantile(0.99, rate(ludwig_outbox_latency_seconds_bucket[5m]))` above the SLO (e.g. 60s) | Delivery is falling behind: destination slow, poller stalled, or backlog |
| `rate(ludwig_outbox_dispatch_failed_total[5m]) / rate(ludwig_outbox_dispatch_succeeded_total[5m]) > 0.1` | A destination is failing; dead letters will follow |

**Known gaps:**

- No gauge for the backlog (`PENDING` count, age of the oldest due message). A poller that stopped
  claiming emits *nothing*, so none of the alerts above fire. Until a gauge exists, monitor with SQL:
  `SELECT count(*), min(next_attempt_at) FROM outbox_message WHERE status = 'PENDING' AND next_attempt_at < now();`
- No health indicator reporting a stalled poller or a dead-letter backlog.

## Audit

Every status transition is recorded through the platform `AuditSink`
([`audit-core`](../audit-core)):

| Field | Value |
|---|---|
| category | `outbox` |
| action | `outbox.<status>` (`outbox.processing`, `outbox.published`, `outbox.dead_letter`, ...) |
| actor | `system` - the transition happens on a scheduler thread; the publishing user is on the business event itself |
| resource | `outbox-message` / message id / event type |
| outcome | `FAILURE` with the error for `DEAD_LETTER` and `FAILED`, `SUCCESS` otherwise |
| attributes | `eventType`, `aggregateType`, `aggregateId`, `attempts`, `fromStatus`, `toStatus` |

The payload is never included: it is the business event in full, and the audit trail is retained longer
and read by more people than the outbox row.

`outbox_status_history` (enabled by `audit.persist-history`) is **operational state**, not an audit
trail: the dispatcher reads attempt counts and last failures from it. It is kept separate from the
platform `audit_event` table so that a deployment shortening its audit retention does not shorten the
dispatcher's memory.

A service that registers its own `OutboxAuditLogger` replaces the shipped one and is responsible for
forwarding to the platform trail.

## Administration

Operator endpoints are on the platform's administrative surface, `/admin/v1/{area}`, and exist only where `security-spring-boot-starter`'s surface does. Each is guarded by one operation; which of the service's roles hold it is the service's own `ludwig.admin.grants` (roles from the authority resolver, never token claims), and an operation granted to no role is refused to everyone. A `privileged` call needs an `Admin-Reason` header, and every call is audited under category `admin` with the operation id as its action. `GET /admin/v1/me/operations` lists what the caller holds.

| Operation | Endpoints | Allows |
|---|---|---|
| `outbox.read` | `GET /admin/v1/outbox/messages`<br>`GET /admin/v1/outbox/messages/{id}`<br>`GET /admin/v1/outbox/summary` | See outbox messages by state, without their payloads. |
| `outbox.operate` | `POST /admin/v1/outbox/messages/{id}/retry`<br>`POST /admin/v1/outbox/messages/retry` | Retry failed and dead-lettered outbox messages. |
| `outbox.privileged` | `POST /admin/v1/outbox/messages/{id}/discard` | Discard an outbox message so that it is never sent. |

**Filtering `GET /admin/v1/outbox/messages`.** The listing takes an OData `$filter` and no other filter parameter; it pages with `cursor` and `size` in a fixed newest-first order, so `$orderby`, `$top`, `$skip` and `$count` are `400`, as is a field not listed here. The filter is applied under the area's own rules and can only narrow them. Filterable fields: `status`, `eventType`, `eventVersion`, `aggregateType`, `aggregateId`, `destination`, `transport`, `attempts`, `createdAt`, `nextAttemptAt`, `publishedAt`, `finishedAt`. Indexed with `status`: `nextAttemptAt` (for `PENDING` and `FAILED`) and `finishedAt`. Not filterable: the payload, `lastError`, the idempotency key, the lock owner. Example: `$filter=status eq 'DEAD_LETTER' and eventType eq 'OrderCreated'`.

Grant block (the role names are the service's own):

```yaml
ludwig:
  admin:
    grants:
      "[outbox.read]": [ORDERS_ADMIN, ORDERS_SUPPORT]
      "[outbox.operate]": [ORDERS_ADMIN]
      "[outbox.privileged]": [ORDERS_ADMIN]
```

## Runbook

| Symptom | Likely cause | What to do |
|---|---|---|
| `ludwig_outbox_deadlettered_total` increases | Destination rejects the payload, or was down longer than the retry budget (10 attempts over ≈ 8.5 min at defaults: 1s, 2s, 4s ... 256s, ±20%) | Find them: `SELECT destination, event_type, last_error, count(*) FROM outbox_message WHERE status = 'DEAD_LETTER' GROUP BY 1, 2, 3;` Fix the destination, then requeue (below) |
| Events not arriving, no dispatch failures, no dead letters | Poller not running: `polling.enabled=false`, instance not started, or all instances stuck | Check backlog SQL in [Known gaps](#observability). If due rows exist, check the service log for the poller, then `ludwig.outbox.polling.*` config |
| Many rows stuck in `PROCESSING` | An instance died holding claims | They return to `PENDING` within `stale-timeout + stale-reclaim-fixed-delay` (6 min at defaults); the `Reclaimed ... stale PROCESSING` warning confirms it. No action needed unless they stay |
| Events for one aggregate stop, others flow | Ordering: an earlier message with the same `ordering_key` is not yet `PUBLISHED` (retrying) | `SELECT id, status, attempts, last_error FROM outbox_message WHERE ordering_key = '<key>' AND status <> 'PUBLISHED' ORDER BY created_at;` Fix the head message; it unblocks the key once published or dead-lettered |
| Startup fails: route names an undeclared cluster | `transport=kafka:<name>` with no `ludwig.kafka.clusters.<name>` | Declare the cluster or fix the route |
| Startup fails: event type declared twice | Two `OutboxEventTypes` beans disagree | Make the declarations agree |
| Test fails: "undeclared event type" | `require-declared=true` and a new event type has no declaration | Add it to the service's `OutboxEventTypes` bean |
| `outbox_message` grows without bound | Retention disabled, no `RunLock` (startup WARN), or the purge cannot keep up | Check `ludwig.outbox.retention.enabled` and the startup log; if `outbox.purged` events stay `PARTIAL`, raise `retention.batch-size` or lower `retention.interval` |

**Requeue dead letters** (after the cause is fixed):

```sql
UPDATE outbox_message
   SET status = 'PENDING', attempts = 0, next_attempt_at = now(), locked_at = NULL, locked_by = NULL
 WHERE status = 'DEAD_LETTER' AND destination = '<destination>';
```

The message is sent again with the same `ludwig-idempotency-key` header; a consumer using the
[messaging](../messaging-spring-boot-starter#consumer-side-dedup) dedup filter drops a copy it already
processed. A consumer without dedup receives it again.

## Testing

- `mvn test` - unit tests (backoff, routing, filtering, serialization, publisher); no Docker.
- `mvn verify` - integration test against a real PostgreSQL in Testcontainers: applies the real
  changelog, validates JPA mappings against it (`ddl-auto=validate`), and exercises concurrent
  `SKIP LOCKED` claiming, per-key ordering, idempotency, retry and dead-lettering, and stale-claim
  recovery. Needs Docker.
- In a consuming service: set `ludwig.outbox.events.require-declared=true` in test properties.

## Design decisions

- **`Propagation.MANDATORY` on `publish`.** An outbox write outside the business transaction is a
  dual write, which is the problem the pattern exists to remove.
- **Backoff with jitter.** Messages that failed during one outage would otherwise all come due at the
  same instant and knock over the destination that had just recovered.
- **Drain on shutdown.** Abandoned claims wait for the stale reclaimer (minutes); draining makes a
  rolling deploy cost nothing.
- **A dead letter releases its ordering key.** Otherwise one poison message stalls its key forever.
- **Payload never in audit or logs.** The event is business data with its own access rules.
- **Shared mechanics in `job-core`.** One backoff curve, one claim statement, one scheduling base for
  every worker, so every worker behaves the same during a partner outage.
