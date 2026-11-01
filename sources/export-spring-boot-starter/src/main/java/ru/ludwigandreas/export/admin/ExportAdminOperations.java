package ru.ludwigandreas.export.admin;

import java.util.Collection;
import java.util.List;
import ru.ludwigandreas.security.operation.Operation;
import ru.ludwigandreas.security.operation.OperationContributor;
import ru.ludwigandreas.security.operation.Sensitivity;

/**
 * The {@code export} administrative area: every requester's runs, cancelling one, and every report subscription.
 */
public final class ExportAdminOperations implements OperationContributor {

    /** See every requester's runs, without any output. */
    public static final String RUN_READ = "export.run.read";

    /** Cancel anybody's run. */
    public static final String RUN_CANCEL = "export.run.cancel";

    /** See every report subscription. */
    public static final String SUBSCRIPTION_READ = "export.subscription.read";

    private static final String KEY = "ludwig.export.admin.";

    @Override
    public Collection<Operation> operations() {
        return List.of(Operation.admin(RUN_READ, Sensitivity.READ, KEY + RUN_READ),
                Operation.admin(RUN_CANCEL, Sensitivity.CHANGE, KEY + RUN_CANCEL),
                Operation.admin(SUBSCRIPTION_READ, Sensitivity.READ, KEY + SUBSCRIPTION_READ));
    }
}
