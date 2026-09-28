package ru.ludwigandreas.reconciliation.exception;

/**
 * An external record could not be written to, or read back from, the staging table.
 *
 * <p>Never retryable. Serializing is deterministic, so a record that cannot be written now will not
 * be writable in thirty seconds; and a staged payload that no longer parses means the task's declared
 * external type changed underneath rows that were already staged, which a human has to resolve.
 */
public class ReconciliationSerializationException extends ReconciliationException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what went wrong
     * @param cause   the underlying Jackson failure
     */
    public ReconciliationSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
