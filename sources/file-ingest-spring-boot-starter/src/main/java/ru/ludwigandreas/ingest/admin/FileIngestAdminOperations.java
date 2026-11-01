package ru.ludwigandreas.ingest.admin;

import java.util.Collection;
import java.util.List;
import ru.ludwigandreas.security.operation.Operation;
import ru.ludwigandreas.security.operation.OperationContributor;
import ru.ludwigandreas.security.operation.Sensitivity;

/**
 * The {@code fileingest} administrative area: ingest tasks, their runs, and triggering a pass.
 */
public final class FileIngestAdminOperations implements OperationContributor {

    /** See ingest tasks and their runs. */
    public static final String TASK_READ = "fileingest.task.read";

    /** Trigger a pass: not destructive, since identity and checkpoint still hold, but unplanned load. */
    public static final String RUN_START = "fileingest.run.start";

    private static final String KEY = "ludwig.ingest.admin.";

    @Override
    public Collection<Operation> operations() {
        return List.of(Operation.admin(TASK_READ, Sensitivity.READ, KEY + TASK_READ),
                Operation.admin(RUN_START, Sensitivity.CHANGE, KEY + RUN_START));
    }
}
