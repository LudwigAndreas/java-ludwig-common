package ru.ludwigandreas.jira.auth;

import ru.ludwigandreas.jira.http.JiraRequest;

/**
 * How a request proves who is making it.
 *
 * <p>An SPI rather than a closed enum, so that a deployment whose Jira sits behind an SSO proxy, or which
 * mints short-lived tokens from a secrets manager, supplies its own implementation without this module
 * growing a dependency on either. Two implementations ship:
 * {@link PersonalAccessTokenCredentials} (Jira Server 8.14+ PATs, the recommended choice) and
 * {@link BasicAuthCredentials}.
 *
 * <p>{@link #apply} runs once per attempt, inside the retry loop, which is what makes
 * {@link PersonalAccessTokenCredentials#rotating} work: a token that expires between the first attempt and
 * the third is re-read rather than re-sent.
 *
 * <p>An implementation must never put the secret into {@link #describe()}. That value goes into log lines
 * and exception messages.
 */
public interface JiraCredentials {

    /** Credentials that send no authentication at all, for a public Jira or an anonymous read. */
    static JiraCredentials anonymous() {
        return AnonymousCredentials.INSTANCE;
    }

    /**
     * Stamps whatever headers this scheme needs onto the request being built.
     *
     * @param request the request under construction
     */
    void apply(JiraRequest.Builder request);

    /**
     * A redaction-safe description of these credentials - the scheme, and at most a non-secret identifier
     * such as the username - used in log lines and in the startup self-check's failure message.
     *
     * @return text safe to write to a log
     */
    String describe();
}
