package ru.ludwigandreas.jira.error;

/**
 * Jira answered 409, or a Structure forest update was rejected because the caller's forest version was
 * stale.
 *
 * <p>The correct reaction is to re-read the current state and reapply the change, not to retry the same
 * request: the request that conflicted was computed from a version the server has moved past.
 */
public class JiraConflictException extends JiraApiException {

    private static final long serialVersionUID = 1L;

    public JiraConflictException(int status, String method, String uri, ErrorCollection errors, String body) {
        super(status, method, uri, errors, body);
    }
}
