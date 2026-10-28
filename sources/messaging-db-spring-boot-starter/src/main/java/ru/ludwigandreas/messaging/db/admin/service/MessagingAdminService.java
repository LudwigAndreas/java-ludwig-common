package ru.ludwigandreas.messaging.db.admin.service;

import java.util.List;
import ru.ludwigandreas.messaging.db.log.LogStore;
import ru.ludwigandreas.messaging.db.log.LogStore.ConsumerPosition;

/**
 * The {@code messaging} area's operations: read-only, answered from the offset tables, never from a replica's
 * in-memory consumer state.
 */
public class MessagingAdminService {

    private final LogStore store;

    /**
     * Creates the service.
     *
     * @param store the database log
     */
    public MessagingAdminService(LogStore store) {
        this.store = store;
    }

    /**
     * Every group's position on every partition.
     *
     * @return the positions, ordered by group, topic and partition
     */
    public List<ConsumerPosition> consumers() {
        return store.positions();
    }
}
