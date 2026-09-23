package ru.ludwigandreas.jira.error;

/**
 * The request never produced an HTTP response: connection refused, DNS failure, TLS handshake failure,
 * socket timeout, or the calling thread was interrupted while waiting.
 *
 * <p>Distinct from {@link JiraApiException} because the two call for opposite reactions. A transport
 * failure on a read is safe to retry; a transport failure on a non-idempotent write may or may not have
 * been applied server-side, which is why {@link ru.ludwigandreas.jira.http.RetryPolicy} retries
 * {@code GET}/{@code HEAD} but not {@code POST} unless the caller opts in.
 */
public class JiraTransportException extends JiraException {

    private static final long serialVersionUID = 1L;

    public JiraTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
