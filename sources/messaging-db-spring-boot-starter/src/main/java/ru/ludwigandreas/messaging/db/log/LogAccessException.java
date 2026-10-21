package ru.ludwigandreas.messaging.db.log;

/** The database holding the log could not be reached or refused a statement. */
public class LogAccessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what was being attempted
     * @param cause   the JDBC failure
     */
    public LogAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
