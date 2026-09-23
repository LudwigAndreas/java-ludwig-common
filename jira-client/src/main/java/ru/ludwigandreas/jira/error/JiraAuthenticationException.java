package ru.ludwigandreas.jira.error;

/**
 * Jira answered 401: the credentials are absent, malformed, expired or revoked.
 *
 * <p>Never retried. A personal access token that has expired produces this on every attempt, and retrying
 * a 401 in a loop is how an integration account gets locked out by a brute-force protection rule.
 */
public class JiraAuthenticationException extends JiraApiException {

    private static final long serialVersionUID = 1L;

    public JiraAuthenticationException(int status, String method, String uri, ErrorCollection errors, String body) {
        super(status, method, uri, errors, body);
    }
}
