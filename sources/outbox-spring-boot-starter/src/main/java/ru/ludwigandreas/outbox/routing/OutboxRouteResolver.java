package ru.ludwigandreas.outbox.routing;

import ru.ludwigandreas.outbox.api.OutboxEvent;

/** Decides which transport/destination an {@link OutboxEvent} is dispatched through. */
public interface OutboxRouteResolver {

    /** @throws ru.ludwigandreas.outbox.exception.OutboxRoutingException if no route can be resolved */
    OutboxRoute resolve(OutboxEvent event);
}
