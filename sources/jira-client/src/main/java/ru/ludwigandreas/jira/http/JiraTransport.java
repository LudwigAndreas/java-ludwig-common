package ru.ludwigandreas.jira.http;

/**
 * The one operation this client needs from an HTTP stack: send a rendered request, return the response.
 *
 * <p>Extracted as an interface so the client can be pointed at something other than the JDK's
 * {@code HttpClient} - an Apache or OkHttp stack a team has already standardized on, a proxy wrapper that
 * injects mutual-TLS, or a recorded fixture in a test - without any of the API, model or retry code
 * changing. {@link ru.ludwigandreas.jira.JiraClientBuilder#transport} is where one is supplied.
 *
 * <p>An implementation must translate every I/O failure into
 * {@link ru.ludwigandreas.jira.error.JiraTransportException} and must not interpret status codes: a 404 is
 * a response, not a failure, and the layer above decides what it means.
 */
@FunctionalInterface
public interface JiraTransport extends AutoCloseable {

    /**
     * Sends the request and reads the full response.
     *
     * @param request the request to send
     * @return the response, whatever its status
     */
    JiraResponse execute(JiraRequest request);

    /**
     * Releases whatever the implementation holds. The default does nothing, because the JDK's
     * {@code HttpClient} has no close in Java 17 and most adapters have nothing to release either.
     */
    @Override
    default void close() {
        // Nothing to release by default.
    }
}
