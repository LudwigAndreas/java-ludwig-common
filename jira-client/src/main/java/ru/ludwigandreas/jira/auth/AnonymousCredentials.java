package ru.ludwigandreas.jira.auth;

import ru.ludwigandreas.jira.http.JiraRequest;

/**
 * Sends no {@code Authorization} header.
 *
 * <p>Useful against a Jira that exposes some projects anonymously, and in tests. Note that an anonymous
 * request to a permission-protected resource comes back 404 rather than 401 - Jira hides existence from
 * users who may not browse - so an accidentally anonymous client looks like a client pointed at the wrong
 * issue keys rather than like an authentication problem. That is the reason
 * {@link ru.ludwigandreas.jira.JiraClientBuilder} requires credentials to be chosen explicitly instead of
 * defaulting to this.
 */
final class AnonymousCredentials implements JiraCredentials {

    static final JiraCredentials INSTANCE = new AnonymousCredentials();

    private AnonymousCredentials() {
    }

    @Override
    public void apply(JiraRequest.Builder request) {
        // No header: that is the whole point.
    }

    @Override
    public String describe() {
        return "anonymous";
    }
}
