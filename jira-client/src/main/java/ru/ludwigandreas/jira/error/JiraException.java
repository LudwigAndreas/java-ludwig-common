package ru.ludwigandreas.jira.error;

/**
 * Base type for every failure this client reports.
 *
 * <p>It is an unchecked exception on purpose. A Jira call fails for reasons the caller almost never has a
 * local recovery for - the server is down, the token expired, the issue was deleted - and forcing a
 * {@code throws} clause through every layer of a service only produces {@code catch (Exception e) { throw
 * new RuntimeException(e); }}. Callers that do have a recovery catch the specific subclass instead:
 * {@link JiraNotFoundException} to treat a missing issue as absent, {@link JiraRateLimitException} to back
 * off, {@link JiraTransportException} to fail over.
 */
public class JiraException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JiraException(String message) {
        super(message);
    }

    public JiraException(String message, Throwable cause) {
        super(message, cause);
    }
}
