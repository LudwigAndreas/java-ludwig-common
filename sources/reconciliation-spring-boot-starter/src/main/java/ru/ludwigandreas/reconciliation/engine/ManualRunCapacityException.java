package ru.ludwigandreas.reconciliation.engine;

/**
 * Every manual-run slot is busy, so an operator's run, dry run or backfill was refused before it started.
 *
 * <p>Thrown on the request thread, after the run's lease has been released again, so nothing is held and nothing ran.
 */
public class ManualRunCapacityException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String task;

    /**
     * @param task the task whose run was refused
     */
    public ManualRunCapacityException(String task) {
        super("Every manual-run slot is busy; the run of task '" + task + "' was not started");
        this.task = task;
    }

    /** The task whose run was refused. */
    public String task() {
        return task;
    }
}
