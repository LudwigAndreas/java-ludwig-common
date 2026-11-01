package ru.ludwigandreas.security.admin;

import java.util.Collection;
import java.util.List;
import ru.ludwigandreas.security.operation.Operation;
import ru.ludwigandreas.security.operation.OperationContributor;
import ru.ludwigandreas.security.operation.Sensitivity;

/** The {@code security} administrative area: who holds which operation. */
public final class SecurityAdminOperations implements OperationContributor {

    /** See which roles hold an operation, and with which scope. */
    public static final String OPERATION_READ = "security.operation.read";

    @Override
    public Collection<Operation> operations() {
        return List.of(Operation.admin(OPERATION_READ, Sensitivity.READ, "ludwig.security.admin." + OPERATION_READ));
    }
}
