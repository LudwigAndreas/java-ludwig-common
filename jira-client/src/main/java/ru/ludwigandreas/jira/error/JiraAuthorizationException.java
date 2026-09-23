package ru.ludwigandreas.jira.error;

/**
 * Jira answered 403: the credentials are valid but the account may not perform this operation.
 *
 * <p>Also what Jira returns when a CAPTCHA challenge has been raised for the account, in which case the
 * response carries an {@code X-Authentication-Denied-Reason} header - see {@link #getBody()} and the
 * message, which quotes it.
 */
public class JiraAuthorizationException extends JiraApiException {

    private static final long serialVersionUID = 1L;

    public JiraAuthorizationException(int status, String method, String uri, ErrorCollection errors, String body) {
        super(status, method, uri, errors, body);
    }
}
