package ru.ludwigandreas.ingest.exception;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * Somebody named a task this service does not have.
 *
 * <p>The one failure in this module with a caller waiting on it: an actuator call with a typo in the
 * task name. It is therefore the one that is localized and rendered by {@code web-core}'s pipeline,
 * while everything else here is a plain {@link IngestException} logged where the run happened.
 *
 * <p>Carries the configured names, because the useful response to "no such task" is the list of the
 * ones there are.
 */
public class UnknownIngestTaskException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    /**
     * A name that matches no configured task.
     *
     * @param name      what was asked for
     * @param available the configured task names, comma-separated
     */
    public UnknownIngestTaskException(String name, String available) {
        super(ProblemStatus.NOT_FOUND, IngestProblemCodes.UNKNOWN_TASK, null, name, available);
        withProperty("task", name);
    }
}
