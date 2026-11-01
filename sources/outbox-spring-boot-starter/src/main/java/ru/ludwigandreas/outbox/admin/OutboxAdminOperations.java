package ru.ludwigandreas.outbox.admin;

import java.util.Collection;
import java.util.List;
import ru.ludwigandreas.security.operation.Operation;
import ru.ludwigandreas.security.operation.OperationContributor;
import ru.ludwigandreas.security.operation.Sensitivity;

/**
 * The {@code outbox} area's administrative operations.
 */
public final class OutboxAdminOperations implements OperationContributor {

    /** Browse messages by state, without payloads, and their per-status summary. */
    public static final String MESSAGE_READ = "outbox.message.read";

    /** Retry failed and dead-lettered messages, one or a capped batch. */
    public static final String MESSAGE_RETRY = "outbox.message.retry";

    /** Discard a message so that it is never sent. Privileged: irrecoverable. */
    public static final String MESSAGE_DISCARD = "outbox.message.discard";

    private static final String KEY = "ludwig.outbox.admin.";

    @Override
    public Collection<Operation> operations() {
        return List.of(Operation.admin(MESSAGE_READ, Sensitivity.READ, KEY + MESSAGE_READ),
                Operation.admin(MESSAGE_RETRY, Sensitivity.CHANGE, KEY + MESSAGE_RETRY),
                Operation.admin(MESSAGE_DISCARD, Sensitivity.PRIVILEGED, KEY + MESSAGE_DISCARD));
    }
}
