package ru.ludwigandreas.restclient.admin.service;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import ru.ludwigandreas.restclient.config.AuthTypes;
import ru.ludwigandreas.restclient.config.CircuitBreakerProperties;
import ru.ludwigandreas.restclient.config.ClientProperties;
import ru.ludwigandreas.restclient.config.ClientRuntimeBuilder;
import ru.ludwigandreas.restclient.config.ResilienceProperties;
import ru.ludwigandreas.restclient.config.RetryProperties;
import ru.ludwigandreas.restclient.core.RestClientRegistry;

/**
 * The {@code restclient} area's listing and the lookups its controls share: each named client's effective
 * configuration, never a credential. Read from configuration, so listing builds no client.
 */
public class RestClientAdminService {

    private final RestClientRegistry clients;
    private final ClientRuntimeBuilder configuration;

    /**
     * Creates the service.
     *
     * @param clients       the named clients
     * @param configuration resolves a client's effective configuration
     */
    public RestClientAdminService(RestClientRegistry clients, ClientRuntimeBuilder configuration) {
        this.clients = clients;
        this.configuration = configuration;
    }

    /**
     * The named clients.
     *
     * @return one entry per client, by name
     */
    public List<ClientView> clients() {
        return clients.names().stream().sorted().map(name -> ClientView.of(name, configuration.resolve(name)))
                .toList();
    }

    /**
     * A named client's effective configuration.
     *
     * @param name the client
     * @return it
     * @throws IllegalArgumentException when no client has that name
     */
    public ClientProperties configured(String name) {
        if (!clients.contains(name)) {
            throw new IllegalArgumentException("no REST client named '" + name + "'");
        }
        return configuration.resolve(name);
    }

    /**
     * Builds {@code name}'s runtime on this replica if it has not been yet, which registers its circuit breaker.
     *
     * @param name the client
     */
    public void ensureBuilt(String name) {
        clients.runtime(name);
    }

    /**
     * One client as an operator sees it: where it calls, how it authenticates, how it is protected.
     *
     * @param name           the client's name
     * @param baseUrlHost    the host of its base URL, or null
     * @param authKind       its {@code auth.type}
     * @param resilience     whether resilience is on at all
     * @param retry          its retry policy
     * @param circuitBreaker its circuit breaker policy
     */
    public record ClientView(String name, String baseUrlHost, String authKind, boolean resilience, RetryView retry,
                             BreakerView circuitBreaker) {

        static ClientView of(String name, ClientProperties properties) {
            ResilienceProperties resilience = properties.getResilience();
            RetryProperties retry = resilience.getRetry();
            CircuitBreakerProperties breaker = resilience.getCircuitBreaker();
            String type = properties.getAuth().getType();
            return new ClientView(name, host(properties.getBaseUrl()), type == null ? AuthTypes.NONE : type,
                    Boolean.TRUE.equals(resilience.getEnabled()),
                    new RetryView(Boolean.TRUE.equals(retry.getEnabled()), retry.getMaxAttempts()),
                    new BreakerView(Boolean.TRUE.equals(breaker.getEnabled()), breaker.getFailureRateThreshold(),
                            breaker.getWaitDurationInOpenState()));
        }

        private static String host(String baseUrl) {
            if (baseUrl == null) {
                return null;
            }
            try {
                return URI.create(baseUrl).getHost();
            } catch (IllegalArgumentException unparsable) {
                // An unparsable base URL is not echoed back: it may carry userinfo.
                return null;
            }
        }
    }

    /**
     * A retry policy.
     *
     * @param enabled     whether calls are retried
     * @param maxAttempts the most attempts per call
     */
    public record RetryView(boolean enabled, Integer maxAttempts) {
    }

    /**
     * A circuit breaker policy.
     *
     * @param enabled                 whether the client has a breaker
     * @param failureRateThreshold    the failure percentage that opens it
     * @param waitDurationInOpenState how long it stays open before letting a probe through
     */
    public record BreakerView(boolean enabled, Float failureRateThreshold, Duration waitDurationInOpenState) {
    }
}
