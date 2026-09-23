package ru.ludwigandreas.restclient.spi;

import org.springframework.core.Ordered;

/**
 * Lifecycle callbacks on every call a named client makes. The second big extension point.
 *
 * <p>Listeners are Spring beans, discovered by type, and are invoked in {@link Ordered} order. They
 * are for observing: correlating a call with a business event, feeding a bespoke SLA dashboard,
 * recording a partner's quota headers. They cannot change the request - {@link OutboundRequest} is
 * immutable - because a listener that could would be an interceptor, and "add a listener" would stop
 * being a safe thing to do.
 *
 * <p><strong>A listener that throws never breaks the call.</strong> The exception is caught, logged
 * once per listener class at WARN, and counted as
 * {@code ludwig.restclient.listener.failures{client,listener,callback}}. The alternative - letting a
 * dashboard integration fail a payment - is not a trade this platform makes. The consequence is that
 * a listener must not be used to enforce anything.
 *
 * <p>Callbacks run on the calling thread for a {@code sync} client and on a Reactor thread for an
 * {@code async} one, so an implementation that blocks makes every call slower by however long it
 * blocks. Hand the work to an executor.
 */
public interface RestClientListener extends Ordered {

    /**
     * Whether this listener applies to {@code clientName}.
     *
     * <p>Defaults to every client. Override it to scope a listener to the one dependency it is about
     * - a listener that reads a partner's {@code X-RateLimit-Remaining} header has no business
     * running on the other nine clients.
     */
    default boolean supports(String clientName) {
        return true;
    }

    /** Before an attempt leaves this service. */
    default void onRequest(OutboundRequest request) {
    }

    /** After an attempt's status line and headers have been read, whatever the status. */
    default void onResponse(OutboundRequest request, OutboundResponse response) {
    }

    /**
     * When an attempt ended in an exception rather than a response - a connect failure, a socket
     * timeout, a TLS handshake rejection - or when the logical call ended in one.
     */
    default void onError(OutboundRequest request, Throwable error) {
    }

    /**
     * After the decision to retry has been taken and before the wait.
     *
     * @param nextAttempt the attempt number about to be made
     * @param waitMillis  how long the pipeline is about to wait, {@code Retry-After} included
     * @param cause       the response status as text, or the exception, that triggered the retry
     */
    default void onRetry(OutboundRequest request, int nextAttempt, long waitMillis, String cause) {
    }

    /** On every circuit-breaker state transition for a client this listener supports. */
    default void onCircuitBreakerStateChange(String clientName, String fromState, String toState) {
    }

    /** Listeners run lowest-first; the default puts an unordered listener after the ordered ones. */
    @Override
    default int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
