package ru.ludwigandreas.jira.error;

/**
 * A response body could not be mapped onto the requested type, or a request body could not be written.
 *
 * <p>In practice this means the server returned something the model does not describe - a field whose type
 * changed between Jira versions, or an HTML error page from a reverse proxy that answered instead of Jira.
 * The message carries the target type and the first part of the offending payload, because a
 * {@code MismatchedInputException} on its own never says which call produced it.
 */
public class JiraSerializationException extends JiraException {

    private static final long serialVersionUID = 1L;

    public JiraSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
