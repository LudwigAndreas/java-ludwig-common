package ru.ludwigandreas.outbox.api;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * Describes one event to publish via {@link OutboxEventPublisher}. Construct with
 * {@code OutboxEvent.builder()...build()} inside the same {@code @Transactional} business method that
 * makes the change this event describes.
 */
@Getter
@Builder
public class OutboxEvent {

    /** Type of the aggregate this event is about (e.g. {@code "Order"}). Used for routing and diagnostics. */
    private final String aggregateType;

    /** Id of the aggregate instance, as a string. */
    private final String aggregateId;

    /** Discriminates the kind of event within the aggregate (e.g. {@code "OrderCreated"}). Drives routing. */
    private final String eventType;

    @Builder.Default
    private final int eventVersion = 1;

    /** Serialized via the configured {@link ru.ludwigandreas.outbox.serialization.OutboxPayloadSerializer}. */
    private final Object payload;

    @Builder.Default
    private final Map<String, String> headers = Map.of();

    /** Messages sharing a non-null key are dispatched in creation order, best-effort. Null = unordered. */
    private final String orderingKey;

    /**
     * Optional dedup key: republishing the same key returns the already-persisted row instead of
     * inserting a duplicate. See {@link OutboxEventPublisher} for the concurrency caveat.
     */
    private final String idempotencyKey;

    /**
     * Optional explicit route name (key into {@code ludwig.outbox.routes.*}), overriding the route
     * resolver's event-type-based lookup.
     */
    private final String route;

    private final String traceId;
}
