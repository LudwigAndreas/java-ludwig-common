package ru.ludwigandreas.restclient.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.List;
import ru.ludwigandreas.restclient.auth.ClientAuthenticatorFactory;
import ru.ludwigandreas.restclient.core.CallContextSource;
import ru.ludwigandreas.restclient.core.ClientRuntime;
import ru.ludwigandreas.restclient.core.RefreshableClientState;
import ru.ludwigandreas.restclient.error.ProblemDetailResponseErrorTranslator;
import ru.ludwigandreas.restclient.error.ResponseErrorTranslation;
import ru.ludwigandreas.restclient.observability.ExchangeLogger;
import ru.ludwigandreas.restclient.observability.ClientRedactor;
import ru.ludwigandreas.restclient.observability.RestClientListeners;
import ru.ludwigandreas.restclient.observability.RestClientMeters;
import ru.ludwigandreas.restclient.observability.audit.AuditRecorder;
import ru.ludwigandreas.restclient.resilience.ClientResiliencePolicy;
import ru.ludwigandreas.restclient.resilience.ResiliencePolicyFactory;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.audit.redaction.SensitivityClassifier;
import ru.ludwigandreas.restclient.spi.ResponseErrorTranslator;
import ru.ludwigandreas.restclient.spi.RestClientListener;

/**
 * Builds the per-client {@link ClientRuntime}: merges the properties, then assembles every
 * collaborator that depends on them.
 *
 * <p>It is the single place where {@code defaults} and a client's own block meet, and therefore the
 * single place that has to be right about inheritance. Everything downstream - the pipelines, the
 * policies, the logger - sees a fully-resolved {@code ClientProperties} and never has to ask what
 * the default was.
 */
public class ClientRuntimeBuilder {

    private final RestClientProperties properties;
    private final ClientAuthenticatorFactory authenticators;
    private final ResiliencePolicyFactory policies;
    private final List<RestClientListener> listeners;
    private final List<ResponseErrorTranslator> translators;
    private final ObjectMapper objectMapper;
    private final RestClientMeters meters;
    private final CallContextSource callContext;
    private final ObservationRegistry observationRegistry;
    private final AuditSinkResolver auditSinks;
    private final SensitivityClassifier sensitivity;
    private final Clock clock;
    private final String applicationName;

    /** Creates the builder from every collaborator a client runtime is assembled out of. */
    // CHECKSTYLE.OFF: ParameterNumber - one collaborator per layer of a client; see NamedClientFactory.
    public ClientRuntimeBuilder(RestClientProperties properties,
                                ClientAuthenticatorFactory authenticators,
                                ResiliencePolicyFactory policies,
                                List<RestClientListener> listeners,
                                List<ResponseErrorTranslator> translators,
                                ObjectMapper objectMapper, RestClientMeters meters,
                                CallContextSource callContext,
                                ObservationRegistry observationRegistry,
                                AuditSinkResolver auditSinks, SensitivityClassifier sensitivity,
                                Clock clock,
                                String applicationName) {
        this.properties = properties;
        this.authenticators = authenticators;
        this.policies = policies;
        this.listeners = listeners;
        this.translators = translators;
        this.objectMapper = objectMapper;
        this.meters = meters;
        this.callContext = callContext;
        this.observationRegistry = observationRegistry;
        this.auditSinks = auditSinks;
        this.sensitivity = sensitivity;
        this.clock = clock;
        this.applicationName = applicationName;
    }
    // CHECKSTYLE.ON: ParameterNumber

    /** The fully merged configuration of {@code clientName}. */
    public ClientProperties resolve(String clientName) {
        ClientProperties declared = properties.getClients().get(clientName);
        if (declared == null) {
            throw new IllegalArgumentException("No REST client named '" + clientName + "' is configured.");
        }
        ClientProperties merged = ClientPropertiesMerger.resolve(properties.getDefaults(), declared);
        if (merged.getUserAgent() == null) {
            merged.setUserAgent(defaultUserAgent(clientName));
        }
        return merged;
    }

    /** Assembles the runtime for {@code clientName}. */
    public ClientRuntime build(String clientName) {
        ClientProperties merged = resolve(clientName);
        RestClientListeners clientListeners = new RestClientListeners(clientName, listeners, meters);
        ClientResiliencePolicy policy = policies.create(clientName, merged);
        publishBreakerTransitions(clientName, policy, clientListeners);

        return ClientRuntime.builder()
                .name(clientName)
                .authenticator(authenticators.create(clientName, merged))
                .resilience(policy)
                .listeners(clientListeners)
                .meters(meters)
                .errorTranslation(new ResponseErrorTranslation(translators,
                        new ProblemDetailResponseErrorTranslator(objectMapper)))
                .callContext(callContext)
                .observationRegistry(observationRegistry)
                .clock(clock)
                .state(refreshableState(clientName, merged))
                // The same method, re-run: it re-reads the bound @ConfigurationProperties, so
                // whatever rebinds them - hot-reload, an actuator refresh - is what makes the
                // refreshable keys take effect.
                .refresher(() -> refreshableState(clientName, resolve(clientName)))
                .build();
    }

    /**
     * Builds the parts of a client that are pure function of its properties.
     *
     * <p>Called once at startup and again whenever {@code ClientRuntime} notices the configuration
     * has moved. Nothing in here holds a socket, a token or a sliding window, which is exactly why
     * these are the keys that do not need a restart.
     */
    private RefreshableClientState refreshableState(String clientName, ClientProperties merged) {
        LoggingProperties logging = merged.getLogging();
        ClientRedactor redactor = new ClientRedactor(clientName, sensitivity,
                concat(logging.getRedactedHeaders(), logging.getAdditionalRedactedHeaders()),
                concat(logging.getRedactedFields(), logging.getAdditionalRedactedFields()),
                logging.getMaxBodySize() == null ? 0 : logging.getMaxBodySize());
        return new RefreshableClientState(
                merged,
                policies.retryPolicy(clientName, merged),
                new ExchangeLogger(clientName, logging.getLevel(),
                        Boolean.TRUE.equals(logging.getLogRequestBeforeCall())),
                redactor,
                new AuditRecorder(clientName, merged.getAudit(),
                        auditSinks.resolve(clientName, merged.getAudit()), clock));
    }

    /**
     * Turns the breaker's own state transitions into a metric and a listener callback.
     *
     * <p>Resilience4j publishes the breaker's state as a gauge, which a monitoring system samples -
     * and a breaker that opens and closes between two scrapes leaves no trace in it at all. That is
     * exactly the event worth alerting on, so it is also counted, and handed to any listener that
     * wants to react to it.
     *
     * <p>Registered once, because the runtime for a client is built once and cached; registering a
     * second consumer on the same registry entry would double every count.
     */
    private void publishBreakerTransitions(String clientName, ClientResiliencePolicy policy,
                                           RestClientListeners clientListeners) {
        if (policy.getCircuitBreaker() == null) {
            return;
        }
        policy.getCircuitBreaker().getEventPublisher().onStateTransition(event -> {
            String from = event.getStateTransition().getFromState().name();
            String to = event.getStateTransition().getToState().name();
            meters.circuitBreakerTransition(clientName, from, to);
            clientListeners.onCircuitBreakerStateChange(from, to);
        });
    }

    /**
     * The default {@code User-Agent}: the service, its version, and the named client.
     *
     * <p>Identifying the caller by dependency and not only by service is what lets a partner - or
     * this platform's own gateway - attribute traffic to a client rather than to a host, which is
     * the difference between "the catalog service is hammering us" and "the catalog service's
     * pricing client is hammering us".
     */
    private String defaultUserAgent(String clientName) {
        return applicationName + " (ludwig-rest-client; client=" + clientName + ")";
    }

    private List<String> concat(List<String> base, List<String> additional) {
        if (additional == null || additional.isEmpty()) {
            return base;
        }
        List<String> out = new java.util.ArrayList<>(base);
        out.addAll(additional);
        return out;
    }

    /**
     * Resolves a client's audit sink: the bean named in its {@code audit.sink}, or the platform's.
     *
     * <p>A resolver rather than a single injected sink because {@code audit.sink} is per client: one service
     * can legitimately send one partner's trail to a vendor's API and the rest to the platform's own sink.
     * The named bean is an {@code AuditSink} - the property was {@code audit.emitter} naming an
     * {@code AuditEventEmitter} before the consolidation, and that SPI is gone.
     */
    @FunctionalInterface
    public interface AuditSinkResolver {

        /** The sink for {@code clientName}. */
        AuditSink resolve(String clientName, AuditProperties properties);
    }
}
