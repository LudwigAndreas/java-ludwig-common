package ru.ludwigandreas.job.core.admin;

import java.util.Collection;
import java.util.List;
import ru.ludwigandreas.security.operation.Operation;
import ru.ludwigandreas.security.operation.OperationContributor;
import ru.ludwigandreas.security.operation.Sensitivity;

/**
 * The {@code replica} administrative area: the live replicas and every command's acknowledgements. Submitting a
 * control is authorized by the operation the control itself declares, in its own module.
 */
public final class ReplicaAdminOperations implements OperationContributor {

    /** See the live replicas. */
    public static final String INSTANCE_READ = "replica.instance.read";

    /** See a command and what each replica answered. */
    public static final String COMMAND_READ = "replica.command.read";

    private static final String KEY = "ludwig.job-core.admin.";

    @Override
    public Collection<Operation> operations() {
        return List.of(Operation.admin(INSTANCE_READ, Sensitivity.READ, KEY + INSTANCE_READ),
                Operation.admin(COMMAND_READ, Sensitivity.READ, KEY + COMMAND_READ));
    }
}
