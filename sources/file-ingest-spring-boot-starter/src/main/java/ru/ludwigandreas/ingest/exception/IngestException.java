package ru.ludwigandreas.ingest.exception;

/**
 * Base for failures of the engine itself, which are not a caller's fault and have no localized text.
 *
 * <p>Deliberately separate from {@code LocalizedException}, the same split the export starter makes:
 * a failure somebody asked for and can act on is localized and rendered in their language; a failure
 * of the machinery is this, and is a generic 500 with the detail in the log. The split is what keeps
 * an internal message about a staging table or a connection pool out of a response body.
 *
 * <p>Almost everything in this module is this rather than localized, because almost nothing here has
 * a caller: a scheduled ingest runs at half past six with nobody waiting on it. The localized types
 * exist for the actuator endpoint, which does have one.
 */
public class IngestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * A failure of the engine.
     *
     * @param message what happened
     */
    public IngestException(String message) {
        super(message);
    }

    /**
     * A failure of the engine, with a cause.
     *
     * @param message what happened
     * @param cause   what threw
     */
    public IngestException(String message, Throwable cause) {
        super(message, cause);
    }
}
