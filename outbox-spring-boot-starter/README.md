# outbox-spring-boot-starter

Enterprise-ready transactional outbox for Java 17 + PostgreSQL + Spring Boot services. Add the
dependency, include the shipped Liquibase changelog, call `OutboxEventPublisher.publish(...)` inside
your existing `@Transactional` business methods, and events reach Kafka/REST/your own custom transport
reliably - no message is ever published without the business change that produced it having committed,
and no business change commits with its event silently lost.

## Quick start

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>outbox-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

Include the shipped changelog in your own Liquibase master changelog (or let it run standalone - see
[Schema](#schema-postgresql-only)):

```xml
<include file="classpath:db/changelog/outbox/outbox-changelog.xml" relativeToChangelogFile="false"/>
```

Publish from inside the transaction that makes the change the event describes:

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

`OutboxEventPublisher.publish` requires an ambient transaction (`Propagation.MANDATORY`) - calling it
outside one throws immediately rather than silently breaking the atomicity guarantee the pattern exists
for.

## What happens next

A background poller (no configuration required - see [Scheduling](#scheduling)) claims due rows with
`SELECT ... FOR UPDATE SKIP LOCKED`, so any number of application instances can run the poller
concurrently and never double-process a row. Each claimed message is dispatched over the transport its
route resolved to (Kafka, REST, or a custom transport you register), and the outcome is recorded:
success moves it to `PUBLISHED`; failure schedules a retry with exponential backoff, or - once
`ludwig.outbox.retry.max-attempts` is exhausted - moves it to `DEAD_LETTER`.

## Features and where they live

| Feature | Package |
|---|---|
| Publishing API, filtering | `api`, `publisher`, `publisher.filter` |
| JSON serialization | `serialization` (Jackson by default, pluggable) |
| Multi-destination routing | `routing` (config-driven, or an explicit `OutboxEvent.route()` override) |
| Kafka / REST dispatch, custom transports | `dispatch`, `dispatch.kafka`, `dispatch.rest` |
| Retry backoff, dead-letter handling | `backoff`, `scheduler` (`OutboxOutcomeRecorder`) |
| Ordering, idempotency | schema/query-level (`entity`, `repository` - no separate package) |
| Scheduled publisher, stale-claim recovery | `scheduler` |
| Audit trail | `audit` (structured logs by default, optional persisted history) |
| Metrics | `metrics` (Micrometer, optional) |
| Configuration | `config` (`OutboxProperties`, `ludwig.outbox.*`) |

## Multi-destination routing

```properties
ludwig.outbox.routes.order-events.transport=KAFKA
ludwig.outbox.routes.order-events.destination=orders-topic
ludwig.outbox.routes.order-events.event-types=OrderCreated,OrderCancelled

ludwig.outbox.routes.audit-events.transport=REST
ludwig.outbox.routes.audit-events.destination=https://audit.example.com/events

ludwig.outbox.default-route.transport=KAFKA
ludwig.outbox.default-route.destination=default-topic
```

Resolution order: `OutboxEvent.route()` (explicit override, by route name) -> the first
`ludwig.outbox.routes.*` entry whose `event-types` contains the event's type -> `ludwig.outbox.default-route`.
Register your own `OutboxRouteResolver`/`OutboxDispatcher` bean to add a transport beyond the built-in
Kafka/REST ones.

## Ordering

Set `OutboxEvent.orderingKey(...)` on events that must be dispatched in order relative to each other
(e.g. all events for one aggregate). The poll query only claims a message once every earlier
(lower `created_at`/`id`) unresolved message sharing its key has reached `PUBLISHED` - best-effort
head-of-line blocking, not a global lock. A message that's exhausted retries and moved to `DEAD_LETTER`
no longer blocks later messages on the same key (a permanently stuck message would otherwise jam the
whole key). Disable globally with `ludwig.outbox.ordering.enabled=false`.

## Idempotency

Set `OutboxEvent.idempotencyKey(...)`; republishing the same key returns the already-persisted row
instead of inserting a duplicate. Enforced by a unique partial index in the schema. A genuinely
concurrent double-publish of the same key aborts the caller's transaction with
`DataIntegrityViolationException` (standard Postgres/JDBC behavior for a constraint violation) - treat
that as "duplicate, already handled" in an at-least-once handler's own error handling. See the Javadoc
on `OutboxEventPublisher` for the full explanation.

## Scheduling

The poller and the stale-claim reclaimer run on a dedicated internal `TaskScheduler` this module
creates itself - no `@EnableScheduling` or `TaskScheduler` bean required from the consuming application.
Disable both with `ludwig.outbox.polling.enabled=false`.

## Schema (PostgreSQL only)

Ships as a Liquibase changelog at `classpath:db/changelog/outbox/outbox-changelog.xml`
(`outbox_message` + `outbox_status_history` tables, JSONB payload/headers, partial indexes for the poll
query, ordering key and idempotency key). Two ways to apply it:

- **Include it in your own changelog** (shown above under Quick start) if you manage migrations yourself.
- **Let it run standalone**: with `liquibase-core` on your classpath, `OutboxLiquibaseAutoConfiguration`
  registers it as a second, independent `SpringLiquibase` bean against your primary `DataSource`,
  separate from your own `spring.liquibase.change-log`. Disable with `ludwig.outbox.liquibase.enabled=false`.

Changeset ids/author (`outbox-NNN`/`ludwig-outbox`) are namespaced so they never collide with your own
changesets in the shared `DATABASECHANGELOG` table.

## Repositories

`OutboxMessageRepository`/`OutboxStatusHistoryRepository` are scanned and given a real
`getByIdOrThrow` implementation entirely by this module's own auto-configuration
(`@EnableJpaRepositories(basePackageClasses = ..., repositoryBaseClass = BaseRepositoryImpl.class)`,
scoped to this module's own packages) - no repository/entity scanning configuration needed in the
consuming application.

## Configuration (`ludwig.outbox.*`)

| Property | Default | Meaning |
|---|---|---|
| `ludwig.outbox.enabled` | `true` | Master switch for the module's autoconfiguration |
| `ludwig.outbox.polling.enabled` | `true` | Enables the scheduled poller and stale-claim reclaimer |
| `ludwig.outbox.polling.fixed-delay` | `5s` | Delay between poll cycles |
| `ludwig.outbox.polling.initial-delay` | `5s` | Delay before the first poll cycle |
| `ludwig.outbox.polling.batch-size` | `100` | Max rows claimed per poll cycle |
| `ludwig.outbox.polling.lock-owner` | hostname + random suffix | Value written to `locked_by` |
| `ludwig.outbox.processing.stale-timeout` | `5m` | A `PROCESSING` row older than this is reclaimed to `PENDING` |
| `ludwig.outbox.processing.stale-reclaim-fixed-delay` | `1m` | How often the stale-reclaim task runs |
| `ludwig.outbox.retry.max-attempts` | `10` | Attempts before dead-lettering |
| `ludwig.outbox.retry.initial-interval` | `1s` | First backoff interval |
| `ludwig.outbox.retry.multiplier` | `2.0` | Backoff growth factor |
| `ludwig.outbox.retry.max-interval` | `5m` | Backoff cap |
| `ludwig.outbox.ordering.enabled` | `true` | Honor `OutboxEvent.orderingKey` |
| `ludwig.outbox.idempotency.enabled` | `true` | Honor `OutboxEvent.idempotencyKey` |
| `ludwig.outbox.dead-letter.enabled` | `true` | Move exhausted messages to `DEAD_LETTER` (else they stay `FAILED`) |
| `ludwig.outbox.audit.persist-history` | `false` | Additionally persist every transition to `outbox_status_history` |
| `ludwig.outbox.metrics.enabled` | `true` | Emit Micrometer metrics (only if Micrometer is on the classpath) |
| `ludwig.outbox.liquibase.enabled` | `true` | Apply the shipped changelog as a standalone `SpringLiquibase` bean |
| `ludwig.outbox.rest.connect-timeout` / `read-timeout` | `5s` / `10s` | REST dispatcher HTTP client timeouts |
| `ludwig.outbox.rest.endpoints.<key>` | - | Logical endpoint name -> URL, for destinations that aren't already a full URL |
| `ludwig.outbox.kafka.send-timeout` | `10s` | Max time to wait for Kafka broker acknowledgement per message |
| `ludwig.outbox.routes.<name>.*` / `ludwig.outbox.default-route.*` | - | See [Multi-destination routing](#multi-destination-routing) |

Beyond publish/filter/dispatch counts and dispatch-call duration, `ludwig.outbox.latency` records the
full end-to-end time from `OutboxMessage.createdAt` to the moment it's recorded `PUBLISHED` - polling
interval, retry backoff and queueing time included, not just the dispatch call itself. This is the metric
worth an SLA/alert on.

## Testing

`mvn test` runs the unit test suite (backoff calculation, route resolution, filtering, serialization,
the publisher) with no external dependencies. The integration test additionally spins up a real
PostgreSQL container via Testcontainers - applying the real Liquibase changelog and cross-checking it
against the JPA mappings via `ddl-auto=validate` - to exercise `FOR UPDATE SKIP LOCKED` concurrent
claiming, per-key ordering, idempotency, retry/dead-lettering and stale-claim recovery end to end; it
needs a working Docker daemon.
