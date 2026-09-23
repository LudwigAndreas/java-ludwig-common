package ru.ludwigandreas.reconciliation.exception;

/**
 * Base for the faults this module raises itself, as opposed to the ones a partner or a task's own
 * code produces. Unchecked: none of them is recoverable at the call site, and every one of them is
 * either a configuration error caught at startup or a programming error in a task.
 */
public class ReconciliationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what went wrong
     */
    public ReconciliationException(String message) {
        super(message);
    }

    /**
     * Creates the exception.
     *
     * @param message what went wrong
     * @param cause   the underlying failure
     */
    public ReconciliationException(String message, Throwable cause) {
        super(message, cause);
    }
}
