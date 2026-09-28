package ru.ludwigandreas.restclient.error;

import lombok.Getter;

/**
 * The call was refused by this service's own resilience policy and never made.
 *
 * <p>An open circuit breaker, a full bulkhead, an exhausted rate limiter. The distinction from every
 * other failure matters more than it looks: nothing was sent, the dependency may be perfectly
 * healthy, and the correct reaction is usually to shed load rather than to escalate. A service that
 * reports this as a 502 is telling its own callers that the partner is down when the truth is that
 * this process decided not to ask.
 */
@Getter
public class RestClientCallNotPermittedException extends RestClientException {

    private static final long serialVersionUID = 1L;

    /** Which policy refused: {@code circuit-breaker}, {@code bulkhead} or {@code rate-limiter}. */
    private final String policy;

    public RestClientCallNotPermittedException(String clientName, String correlationId, String message,
                                               String policy, Throwable cause) {
        super(clientName, correlationId, message, cause);
        this.policy = policy;
    }
}
