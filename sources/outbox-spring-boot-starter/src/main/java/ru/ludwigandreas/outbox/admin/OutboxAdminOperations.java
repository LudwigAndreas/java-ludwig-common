package ru.ludwigandreas.outbox.admin;

import java.util.Collection;
import java.util.List;
import ru.ludwigandreas.security.admin.AdminOperation;
import ru.ludwigandreas.security.admin.AdminOperationContributor;

/**
 * The {@code outbox} area's administrative operations.
 */
public final class OutboxAdminOperations implements AdminOperationContributor {

    /** Browse messages by state, without payloads. */
    public static final String READ = "outbox.read";

    /** Retry failed and dead-lettered messages, one or a capped batch. */
    public static final String OPERATE = "outbox.operate";

    /** Discard a message so that it is never sent. Privileged: irrecoverable. */
    public static final String PRIVILEGED = "outbox.privileged";

    private static final String KEY = "ludwig.outbox.admin.";

    @Override
    public Collection<AdminOperation> adminOperations() {
        return List.of(AdminOperation.of(READ, KEY + READ), AdminOperation.of(OPERATE, KEY + OPERATE),
                AdminOperation.of(PRIVILEGED, KEY + PRIVILEGED));
    }
}
