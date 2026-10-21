package ru.ludwigandreas.outbox.dispatch;

import java.util.List;

/**
 * Several dispatchers contributed as one bean, for transports whose number is configuration - one Kafka
 * transport per declared cluster - rather than code.
 */
@FunctionalInterface
public interface OutboxDispatcherGroup {

    /**
     * The dispatchers.
     *
     * @return the dispatchers, each with its own transport
     */
    List<OutboxDispatcher> dispatchers();
}
