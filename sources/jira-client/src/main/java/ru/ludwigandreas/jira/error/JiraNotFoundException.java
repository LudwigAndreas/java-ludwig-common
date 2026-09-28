package ru.ludwigandreas.jira.error;

/**
 * Jira answered 404.
 *
 * <p>Worth catching specifically: Jira deliberately returns 404 rather than 403 for an issue the caller
 * may not browse, so "not found" and "not permitted to see" are indistinguishable here. Treat it as
 * "not visible to this account" rather than as "does not exist".
 */
public class JiraNotFoundException extends JiraApiException {

    private static final long serialVersionUID = 1L;

    public JiraNotFoundException(int status, String method, String uri, ErrorCollection errors, String body) {
        super(status, method, uri, errors, body);
    }
}
