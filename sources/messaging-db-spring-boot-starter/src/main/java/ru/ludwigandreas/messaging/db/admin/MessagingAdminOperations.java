package ru.ludwigandreas.messaging.db.admin;

import java.util.Collection;
import java.util.List;
import ru.ludwigandreas.security.operation.Operation;
import ru.ludwigandreas.security.operation.OperationContributor;
import ru.ludwigandreas.security.operation.Sensitivity;

/**
 * The {@code messaging} administrative area: database-log consumer lag, pausing a consumer group on a topic, and
 * moving a paused group's position.
 */
public final class MessagingAdminOperations implements OperationContributor {

    /** See each consumer group's lag, retry state and pauses. */
    public static final String CONSUMER_READ = "messaging.consumer.read";

    /** Pause a consumer group on a topic, on every replica. */
    public static final String CONSUMER_PAUSE = "messaging.consumer.pause";

    /** Resume a paused consumer group on a topic. */
    public static final String CONSUMER_RESUME = "messaging.consumer.resume";

    /** Move a paused group's position. Privileged: records are skipped or replayed. */
    public static final String OFFSET_MOVE = "messaging.offset.move";

    private static final String KEY = "ludwig.messaging-db.admin.";

    @Override
    public Collection<Operation> operations() {
        return List.of(Operation.admin(CONSUMER_READ, Sensitivity.READ, KEY + CONSUMER_READ),
                Operation.admin(CONSUMER_PAUSE, Sensitivity.CHANGE, KEY + CONSUMER_PAUSE),
                Operation.admin(CONSUMER_RESUME, Sensitivity.CHANGE, KEY + CONSUMER_RESUME),
                Operation.admin(OFFSET_MOVE, Sensitivity.PRIVILEGED, KEY + OFFSET_MOVE));
    }
}
