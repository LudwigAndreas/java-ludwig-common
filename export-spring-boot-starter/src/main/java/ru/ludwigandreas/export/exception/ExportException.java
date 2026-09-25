package ru.ludwigandreas.export.exception;

/**
 * Base for the failures of this module that are not a client's fault and have no localized text.
 *
 * <p>Deliberately separate from {@link ru.ludwigandreas.webcore.problem.LocalizedException}: a
 * failure a requester can act on - an unknown key, a format that is not allowed, a quota - is a
 * localized exception that the problem pipeline renders in their language, while a failure of the
 * engine itself is this, and is rendered as a generic 500 with the detail in the log. The split is
 * what keeps an internal message about a temp directory or a connection pool from being sent to
 * whoever asked for a spreadsheet.
 */
public class ExportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ExportException(String message) {
        super(message);
    }

    public ExportException(String message, Throwable cause) {
        super(message, cause);
    }
}
