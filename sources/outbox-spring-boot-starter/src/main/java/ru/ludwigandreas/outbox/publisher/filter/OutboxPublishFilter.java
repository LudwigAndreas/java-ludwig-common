package ru.ludwigandreas.outbox.publisher.filter;

import ru.ludwigandreas.outbox.api.OutboxEvent;

/**
 * Register a bean of this type to veto publishing certain events (feature flags, tenant scoping, ...).
 * Evaluated before serialization/insert; a {@code false} means {@link ru.ludwigandreas.outbox.api.OutboxEventPublisher}
 * silently skips the event (no row written).
 */
public interface OutboxPublishFilter {

    boolean shouldPublish(OutboxEvent event);
}
