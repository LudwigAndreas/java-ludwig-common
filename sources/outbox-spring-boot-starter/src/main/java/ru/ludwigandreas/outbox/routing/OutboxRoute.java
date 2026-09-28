package ru.ludwigandreas.outbox.routing;

/**
 * Resolved transport + destination for one event, stored on {@link ru.ludwigandreas.outbox.entity.OutboxMessage}
 * at publish time. {@code transport} is looked up in {@link ru.ludwigandreas.outbox.dispatch.OutboxDispatcherRegistry}
 * at dispatch time (case-insensitively, e.g. {@code "KAFKA"}, {@code "REST"}, or any custom transport a
 * consumer registers its own {@link ru.ludwigandreas.outbox.dispatch.OutboxDispatcher} bean for).
 */
public record OutboxRoute(String transport, String destination) {
}
