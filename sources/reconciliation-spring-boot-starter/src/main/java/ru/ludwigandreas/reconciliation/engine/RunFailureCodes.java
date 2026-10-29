package ru.ludwigandreas.reconciliation.engine;

/** The failure codes a recorded run can end with; each has a message in the module's bundle. */
public final class RunFailureCodes {

    /** The pass threw. */
    public static final String RUN_FAILED = "ludwig.reconciliation.error.run-failed";

    /** The pass reached its {@code run-timeout} and stopped early. */
    public static final String RUN_TIMED_OUT = "ludwig.reconciliation.error.run-timed-out";

    /** The run outlived its lease, which only happens when its replica died. */
    public static final String RUN_ABANDONED = "ludwig.reconciliation.error.run-abandoned";

    private RunFailureCodes() {
    }
}
