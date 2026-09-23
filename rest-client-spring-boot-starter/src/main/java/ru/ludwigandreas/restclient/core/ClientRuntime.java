package ru.ludwigandreas.restclient.core;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ludwigandreas.restclient.config.ClientProperties;
import ru.ludwigandreas.restclient.config.RateLimiterProperties;
import ru.ludwigandreas.restclient.error.ResponseErrorTranslation;
import ru.ludwigandreas.restclient.observability.ExchangeLogger;
import ru.ludwigandreas.restclient.observability.HeaderRedactor;
import ru.ludwigandreas.restclient.observability.RestClientListeners;
import ru.ludwigandreas.restclient.observability.RestClientMeters;
import ru.ludwigandreas.restclient.observability.audit.AuditRecorder;
import ru.ludwigandreas.restclient.resilience.ClientResiliencePolicy;
import ru.ludwigandreas.restclient.spi.ClientAuthenticator;

/**
 * Everything one named client needs on the request path, assembled once at startup.
 *
 * <p>It exists so that the pipelines take a single collaborator rather than a dozen, and - more
 * importantly - so that both pipelines take the <em>same</em> one. A {@code sync} and an
 * {@code async} client with identical configuration share every decision-making object here: the
 * same resilience policy, the same authenticator, the same redactor, the same listeners. The two
 * pipelines differ only in how they wait, which is the only thing that should differ between them.
 *
 * <h2>What can change without a restart, and what cannot</h2>
 *
 * <p>The fields below split in two. The immutable half is everything with live state or a socket
 * behind it: the authenticator and its token cache, the connection pool, the circuit breaker's
 * sliding window, the bulkhead's permits. Replacing any of those under a running client would reset
 * the very state that makes them useful, and would leave calls in flight on the old object.
 *
 * <p>The other half - {@link RefreshableClientState} - is pure function of the properties: the retry
 * decision, the log level, the redaction lists, the audit sampling rate. Those are rebuilt from the
 * bound {@code @ConfigurationProperties} when {@link #refreshIfStale()} notices the configuration
 * has moved, so a service using {@code hot-reload-spring-boot-starter} can raise a log level or drop
 * a retry count during an incident without a restart.
 *
 * <p>The check is throttled to {@link #REFRESH_INTERVAL} and is one volatile read on the fast path.
 * It is deliberately not an event subscription: the bound properties object is mutated in place by
 * whatever rebinds it, and there is no single event every rebinding mechanism publishes.
 */
public class ClientRuntime {

    private static final Logger log = LoggerFactory.getLogger(ClientRuntime.class);

    /**
     * How often the configuration is re-read.
     *
     * <p>One second: short enough that a change made during an incident takes effect before anyone
     * has finished typing the next command, long enough that the re-merge - a few dozen small
     * allocations - happens once per second per client rather than once per request.
     */
    private static final Duration REFRESH_INTERVAL = Duration.ofSeconds(1);

    /** The client name: injection qualifier, metric tag, log field, audit field. */
    @Getter
    private final String name;

    @Getter
    private final ClientAuthenticator authenticator;

    @Getter
    private final ClientResiliencePolicy resilience;

    @Getter
    private final RestClientListeners listeners;

    @Getter
    private final RestClientMeters meters;

    @Getter
    private final ResponseErrorTranslation errorTranslation;

    @Getter
    private final CallContextSource callContext;

    /**
     * The registry the blocking pipeline reads the current observation from, to recover the URI
     * template. Never used to start an observation - Spring's client already does that.
     */
    @Getter
    private final ObservationRegistry observationRegistry;

    @Getter
    private final Clock clock;

    private final Supplier<RefreshableClientState> refresher;
    private final AtomicLong lastRefreshNanos;

    private volatile RefreshableClientState state;

    // CHECKSTYLE.OFF: ParameterNumber - one collaborator per layer of a client; the builder below is
    // what call sites actually use.
    ClientRuntime(String name, ClientAuthenticator authenticator, ClientResiliencePolicy resilience,
                  RestClientListeners listeners, RestClientMeters meters,
                  ResponseErrorTranslation errorTranslation, CallContextSource callContext,
                  ObservationRegistry observationRegistry, Clock clock,
                  RefreshableClientState state, Supplier<RefreshableClientState> refresher) {
        this.name = name;
        this.authenticator = authenticator;
        this.resilience = resilience;
        this.listeners = listeners;
        this.meters = meters;
        this.errorTranslation = errorTranslation;
        this.callContext = callContext;
        this.observationRegistry = observationRegistry;
        this.clock = clock;
        this.state = state;
        this.refresher = refresher;
        this.lastRefreshNanos = new AtomicLong(System.nanoTime());
    }
    // CHECKSTYLE.ON: ParameterNumber

    /** A builder, so the twelve constructor arguments are named at the one call site that has them. */
    public static ClientRuntimeBuilder builder() {
        return new ClientRuntimeBuilder();
    }

    /** The merged configuration - see {@code ClientPropertiesMerger}. */
    public ClientProperties getProperties() {
        return state.properties();
    }

    /** The per-client exchange logger. */
    public ExchangeLogger getExchangeLogger() {
        return state.exchangeLogger();
    }

    /** The per-client header and body redactor. */
    public HeaderRedactor getRedactor() {
        return state.redactor();
    }

    /** The per-client audit recorder. */
    public AuditRecorder getAudit() {
        return state.audit();
    }

    /**
     * Re-reads the configuration if it has not been read recently, and swaps in what changed.
     *
     * <p>Called once per logical call by both pipelines. The common path is one volatile read and an
     * arithmetic comparison; only one caller per interval does any work, and a caller that loses the
     * race proceeds with the state it already has rather than waiting.
     */
    public void refreshIfStale() {
        if (refresher == null) {
            return;
        }
        long now = System.nanoTime();
        long last = lastRefreshNanos.get();
        if (now - last < REFRESH_INTERVAL.toNanos() || !lastRefreshNanos.compareAndSet(last, now)) {
            return;
        }
        try {
            RefreshableClientState refreshed = refresher.get();
            applyRateLimits(refreshed.properties());
            resilience.updateRetry(refreshed.retry());
            this.state = refreshed;
        } catch (RuntimeException ex) {
            // A configuration that has become invalid must not take the client down with it. The
            // previous state keeps working, which is the same answer a restart-only client would
            // have given, and the warning is what says the new values are not in effect.
            log.warn("Client '{}': the configuration could not be re-read; the previous one stays in "
                    + "effect.", name, ex);
        }
    }

    /**
     * Applies changed rate limits to the live limiter.
     *
     * <p>Resilience4j supports exactly these two at runtime, which is also the honest boundary: the
     * refresh period is the limiter's internal cycle and changing it would mean discarding the
     * current one, so it requires a restart and the README says so.
     */
    private void applyRateLimits(ClientProperties properties) {
        RateLimiter limiter = resilience.getRateLimiter();
        if (limiter == null) {
            return;
        }
        RateLimiterProperties configured = properties.getResilience().getRateLimiter();
        if (limiter.getRateLimiterConfig().getLimitForPeriod() != configured.getLimitForPeriod()) {
            limiter.changeLimitForPeriod(configured.getLimitForPeriod());
        }
        if (!limiter.getRateLimiterConfig().getTimeoutDuration().equals(configured.getTimeoutDuration())) {
            limiter.changeTimeoutDuration(configured.getTimeoutDuration());
        }
    }

    /** Assembles a {@link ClientRuntime}; every field is required except the refresher. */
    public static final class ClientRuntimeBuilder {

        private String name;
        private ClientAuthenticator authenticator;
        private ClientResiliencePolicy resilience;
        private RestClientListeners listeners;
        private RestClientMeters meters;
        private ResponseErrorTranslation errorTranslation;
        private CallContextSource callContext;
        private ObservationRegistry observationRegistry;
        private Clock clock;
        private RefreshableClientState state;
        private Supplier<RefreshableClientState> refresher;

        private ClientRuntimeBuilder() {
        }

        /** The client name. */
        public ClientRuntimeBuilder name(String value) {
            this.name = value;
            return this;
        }

        /** The authenticator, created once and shared by every caller of this client. */
        public ClientRuntimeBuilder authenticator(ClientAuthenticator value) {
            this.authenticator = value;
            return this;
        }

        /** The resilience decorators. */
        public ClientRuntimeBuilder resilience(ClientResiliencePolicy value) {
            this.resilience = value;
            return this;
        }

        /** The listeners that apply to this client, already filtered and ordered. */
        public ClientRuntimeBuilder listeners(RestClientListeners value) {
            this.listeners = value;
            return this;
        }

        /** The shared meter facade. */
        public ClientRuntimeBuilder meters(RestClientMeters value) {
            this.meters = value;
            return this;
        }

        /** The translator chain for failed responses. */
        public ClientRuntimeBuilder errorTranslation(ResponseErrorTranslation value) {
            this.errorTranslation = value;
            return this;
        }

        /** The ambient correlation, trace and principal source. */
        public ClientRuntimeBuilder callContext(CallContextSource value) {
            this.callContext = value;
            return this;
        }

        /** The registry the URI template is recovered from. */
        public ClientRuntimeBuilder observationRegistry(ObservationRegistry value) {
            this.observationRegistry = value;
            return this;
        }

        /** The clock every timing decision measures against. */
        public ClientRuntimeBuilder clock(Clock value) {
            this.clock = value;
            return this;
        }

        /** The initial refreshable state. */
        public ClientRuntimeBuilder state(RefreshableClientState value) {
            this.state = value;
            return this;
        }

        /** How to rebuild the refreshable state; {@code null} makes the client restart-only. */
        public ClientRuntimeBuilder refresher(Supplier<RefreshableClientState> value) {
            this.refresher = value;
            return this;
        }

        /** Builds the runtime. */
        public ClientRuntime build() {
            return new ClientRuntime(name, authenticator, resilience, listeners, meters,
                    errorTranslation, callContext, observationRegistry, clock, state, refresher);
        }
    }
}
