package ru.ludwigandreas.restclient.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

/**
 * The counters this module publishes alongside the request timer.
 *
 * <p>The timer itself - {@code ludwig.restclient.requests} - comes from Spring's own client
 * observation, retagged by {@link LudwigClientRequestObservationConvention}, so that the metric and
 * the client span describe the same thing and carry the same URI template. These are the events the
 * timer cannot express, because each of them happens either inside a call or outside all of them:
 *
 * <ul>
 *   <li>{@code ludwig.restclient.retries{client,reason}} - attempts beyond the first. Divided by the
 *       request count it is the retry rate, which is the earliest sign a dependency is degrading,
 *       and it rises before the error rate does because the retries are still succeeding.</li>
 *   <li>{@code ludwig.restclient.circuitbreaker.transitions{client,from,to}} - counted here as well
 *       as by Resilience4j's own state gauge, because a gauge scraped every 15 seconds misses a
 *       breaker that opened and closed between scrapes, and that is exactly the event worth
 *       alerting on.</li>
 *   <li>{@code ludwig.restclient.auth.token.refreshes{client,type}} - one per token actually minted.
 *       A rate far above one per token lifetime means the cache is not working.</li>
 *   <li>{@code ludwig.restclient.calls.notpermitted{client,policy}} - calls this service refused to
 *       make. Load shed, not errors, and graphing them as errors is how a healthy bulkhead looks
 *       like an outage.</li>
 *   <li>{@code ludwig.restclient.listener.failures{client,listener,callback}} - a listener threw.
 *       Counted because the exception is swallowed, and a swallowed exception with no metric is an
 *       integration that has been silently dead for months.</li>
 *   <li>{@code ludwig.restclient.audit.failures{client}} - an audit sink threw. Same reasoning, with
 *       a compliance bill attached.</li>
 * </ul>
 *
 * <p>No credential, token, header value or expanded URI appears in any tag. Tags are indexed and
 * retained by every monitoring system in the estate; a secret in one is a secret in all of them.
 */
public class RestClientMeters {

    /** Prefix shared by every meter this module publishes, and by the URI-cardinality filter. */
    public static final String PREFIX = "ludwig.restclient.";

    /** The request timer's name - the meter Spring's observation produces under our convention. */
    public static final String REQUESTS = PREFIX + "requests";

    private static final String RETRIES = PREFIX + "retries";
    private static final String TRANSITIONS = PREFIX + "circuitbreaker.transitions";
    private static final String TOKEN_REFRESHES = PREFIX + "auth.token.refreshes";
    private static final String NOT_PERMITTED = PREFIX + "calls.notpermitted";
    private static final String LISTENER_FAILURES = PREFIX + "listener.failures";
    private static final String AUDIT_FAILURES = PREFIX + "audit.failures";

    private final MeterRegistry registry;

    public RestClientMeters(MeterRegistry registry) {
        this.registry = registry;
    }

    /** One attempt beyond the first. */
    public void retry(String clientName, String reason) {
        counter(RETRIES, Tags.of("client", clientName, "reason", reason));
    }

    /** One circuit-breaker state transition. */
    public void circuitBreakerTransition(String clientName, String from, String to) {
        counter(TRANSITIONS, Tags.of("client", clientName, "from", from, "to", to));
    }

    /** One token actually minted - not one token used. */
    public void tokenRefresh(String clientName, String authType) {
        counter(TOKEN_REFRESHES, Tags.of("client", clientName, "type", authType));
    }

    /** One call refused by a resilience policy and never made. */
    public void callNotPermitted(String clientName, String policy) {
        counter(NOT_PERMITTED, Tags.of("client", clientName, "policy", policy));
    }

    /** One listener callback that threw. */
    public void listenerFailure(String clientName, String listener, String callback) {
        counter(LISTENER_FAILURES,
                Tags.of("client", clientName, "listener", listener, "callback", callback));
    }

    /** One audit emission that threw. */
    public void auditFailure(String clientName) {
        counter(AUDIT_FAILURES, Tags.of("client", clientName));
    }

    private void counter(String name, Tags tags) {
        Counter.builder(name).tags(tags).register(registry).increment();
    }
}
