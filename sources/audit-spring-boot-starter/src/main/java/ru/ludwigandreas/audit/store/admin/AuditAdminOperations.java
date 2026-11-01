package ru.ludwigandreas.audit.store.admin;

import java.util.Collection;
import java.util.List;
import ru.ludwigandreas.security.operation.Operation;
import ru.ludwigandreas.security.operation.OperationContributor;
import ru.ludwigandreas.security.operation.Sensitivity;

/**
 * The {@code audit} administrative area: searching the stored trail. The trail is append-only, so there is nothing to
 * change.
 */
public final class AuditAdminOperations implements OperationContributor {

    /** Search the stored audit trail; every search is itself recorded. */
    public static final String EVENT_READ = "audit.event.read";

    private static final String KEY = "ludwig.audit.admin.";

    @Override
    public Collection<Operation> operations() {
        return List.of(Operation.admin(EVENT_READ, Sensitivity.READ, KEY + EVENT_READ));
    }
}
