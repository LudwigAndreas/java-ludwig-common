package ru.ludwigandreas.restclient.config;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import ru.ludwigandreas.restclient.auth.ClientAuthenticatorFactory;

/**
 * Refuses to start on a configuration that is individually valid and jointly wrong.
 *
 * <p>Bean Validation checks one field at a time, which catches a negative pool size and misses every
 * interesting mistake a client can have. The checks below are all relationships - between a timeout
 * and a retry budget, between a mode and a feature, between a TLS setting and a profile - and each
 * produces behaviour that is intermittent, rare, and nearly impossible to attribute weeks after the
 * change that caused it. Failing the pod at startup is the cheapest way to learn about them.
 *
 * <p>Every message states what breaks rather than what is wrong, and names the property to change,
 * because the person reading it is mid-deployment and needs to know whether to roll back.
 *
 * <p>Problems are collected and reported together. Reporting only the first means a deployment that
 * has three mistakes takes three rollouts to find them.
 */
public class RestClientConfigurationValidator {

    private static final Logger log = LoggerFactory.getLogger(RestClientConfigurationValidator.class);

    /** How much of the retry budget the read timeout must be able to accommodate. */
    private static final int RETRY_BUDGET_HEADROOM = 1;

    private final RestClientProperties properties;
    private final ClientRuntimeBuilder runtimeBuilder;
    private final Environment environment;
    private final ClassLoader classLoader;
    private final ClientAuthenticatorFactory authenticators;

    /** Creates the validator; nothing is checked until {@link #validate()} runs. */
    // CHECKSTYLE.OFF: ParameterNumber - configuration plus the four things a check needs to consult.
    public RestClientConfigurationValidator(RestClientProperties properties,
                                            ClientRuntimeBuilder runtimeBuilder, Environment environment,
                                            ClassLoader classLoader,
                                            ClientAuthenticatorFactory authenticators) {
        this.properties = properties;
        this.runtimeBuilder = runtimeBuilder;
        this.environment = environment;
        this.classLoader = classLoader;
        this.authenticators = authenticators;
    }
    // CHECKSTYLE.ON: ParameterNumber

    /**
     * Checks every configured client and fails the context if any check does not hold.
     *
     * @throws IllegalStateException listing every problem found, rather than only the first
     */
    @PostConstruct
    public void validate() {
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (properties.getDefaults().getBaseUrl() != null) {
            problems.add("defaults.base-url is set. A base URL shared by every client is wrong for "
                    + "all but one of them; set it per client.");
        }
        properties.getClients().forEach((name, declared) ->
                validateClient(name, problems, warnings));

        if (!problems.isEmpty()) {
            throw new IllegalStateException(RestClientProperties.PREFIX
                    + " configuration is unsafe:\n  - " + String.join("\n  - ", problems));
        }
        warnings.forEach(warning -> log.warn("{}: {}", RestClientProperties.PREFIX, warning));
        log.info("{} validated: {} client(s) - {}", RestClientProperties.PREFIX,
                properties.getClients().size(), String.join(", ", properties.getClients().keySet()));
    }

    private void validateClient(String name, List<String> problems, List<String> warnings) {
        ClientProperties merged;
        try {
            merged = runtimeBuilder.resolve(name);
        } catch (RuntimeException ex) {
            problems.add("client '" + name + "' could not be resolved: " + ex.getMessage());
            return;
        }
        checkBaseUrl(name, merged, problems);
        checkTransport(name, merged, problems, warnings);
        checkTimeouts(name, merged, problems);
        checkMode(name, merged, problems);
        checkTls(name, merged, problems);
        checkAuth(name, merged, problems, warnings);
        checkResilience(name, merged, problems, warnings);
    }

    private void checkBaseUrl(String name, ClientProperties merged, List<String> problems) {
        String baseUrl = merged.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            problems.add("client '" + name + "' has no base-url. Every call it makes would be "
                    + "resolved against nothing.");
            return;
        }
        if (baseUrl.startsWith("http://") && sendsCredentials(merged) && !isLoopback(baseUrl)) {
            problems.add("client '" + name + "' sends credentials over plain HTTP (base-url "
                    + baseUrl + "). The credential is readable by anything on the path. Use https, "
                    + "or set auth.type: none.");
        }
    }

    /**
     * Whether the URL never leaves this machine.
     *
     * <p>The carve-out exists because the rule it relaxes - never send a credential over cleartext -
     * is about what can be read on the path, and a loopback address has no path. Without it, every
     * developer testing against a local stub and every integration test with a stub server would
     * have to weaken the check globally, which is how the check stops existing.
     */
    private boolean isLoopback(String baseUrl) {
        try {
            String host = java.net.URI.create(baseUrl).getHost();
            return "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host)
                    || "[::1]".equals(host);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private boolean sendsCredentials(ClientProperties merged) {
        String type = merged.getAuth().getType();
        return type != null && !AuthTypes.NONE.equalsIgnoreCase(type);
    }

    private void checkTransport(String name, ClientProperties merged, List<String> problems,
                                List<String> warnings) {
        TransportEngine engine = merged.getTransport();
        if (!engine.isAvailable(classLoader)) {
            problems.add("client '" + name + "' asks for transport " + engine.name().toLowerCase(
                    Locale.ROOT).replace('_', '-') + ", which is not on the classpath. Add "
                    + engine.requiredArtifact() + ".");
            return;
        }
        if (engine == TransportEngine.HTTP_CLIENT && declaresPool(name)) {
            warnings.add("client '" + name + "' configures a pool, but the JDK HTTP client exposes "
                    + "no pool settings and will ignore them. Use transport: apache, or remove the "
                    + "block so it does not look effective.");
        }
        if (engine == TransportEngine.REACTOR_NETTY && merged.getMode() != ClientMode.ASYNC) {
            problems.add("client '" + name + "' asks for transport reactor-netty with mode=sync. A "
                    + "blocking call on a Netty event loop is the one arrangement that turns a slow "
                    + "dependency into a dead service. Set mode: async, or choose http-client or "
                    + "apache.");
            return;
        }
        if (engine == TransportEngine.APACHE && Boolean.TRUE.equals(merged.getHttp2())) {
            warnings.add("client '" + name + "' asks for http2 on the apache transport, whose "
                    + "classic client speaks HTTP/1.1 only. The request will be HTTP/1.1. Use "
                    + "transport: http-client, or mode: async.");
        }
    }

    /**
     * Whether anyone actually wrote a pool block for this client.
     *
     * <p>Asked of the raw property blocks rather than of the merged view, because the merged view
     * always has pool values - they come from the built-in defaults - and asking it would warn every
     * client on the JDK transport about settings nobody wrote.
     */
    private boolean declaresPool(String name) {
        return declaredPool(properties.getClients().get(name)) || declaredPool(properties.getDefaults());
    }

    private boolean declaredPool(ClientProperties declared) {
        if (declared == null) {
            return false;
        }
        PoolProperties pool = declared.getPool();
        return pool.getMaxTotal() != null || pool.getMaxPerRoute() != null
                || pool.getTimeToLive() != null || pool.getIdleEviction() != null
                || pool.getValidateAfterInactivity() != null || pool.getKeepAlive() != null;
    }

    private void checkTimeouts(String name, ClientProperties merged, List<String> problems) {
        RetryProperties retry = merged.getResilience().getRetry();
        if (!Boolean.TRUE.equals(retry.getEnabled()) || retry.getMaxAttempts() <= 1) {
            return;
        }
        Duration budget = retry.getMaxElapsedTime();
        Duration oneAttempt = merged.getRequestTimeout() != null
                ? merged.getRequestTimeout() : merged.getReadTimeout();
        if (budget.compareTo(oneAttempt.multipliedBy(RETRY_BUDGET_HEADROOM + 1)) < 0) {
            problems.add("client '" + name + "': resilience.retry.max-elapsed-time (" + budget
                    + ") cannot accommodate two attempts at " + oneAttempt + " each, so the retry "
                    + "would never happen and max-attempts=" + retry.getMaxAttempts() + " is a lie. "
                    + "Raise max-elapsed-time, or lower read-timeout/request-timeout.");
        }
        if (merged.getRequestTimeout() != null
                && merged.getRequestTimeout().compareTo(merged.getConnectTimeout()) <= 0) {
            problems.add("client '" + name + "': request-timeout (" + merged.getRequestTimeout()
                    + ") is not longer than connect-timeout (" + merged.getConnectTimeout()
                    + "). Every call would be cancelled before the connection could be established.");
        }
    }

    private void checkMode(String name, ClientProperties merged, List<String> problems) {
        ResilienceProperties resilience = merged.getResilience();
        if (merged.getMode() == ClientMode.ASYNC) {
            if (resilience.getBulkhead().getType() == BulkheadType.THREAD_POOL
                    && Boolean.TRUE.equals(resilience.getBulkhead().getEnabled())) {
                problems.add("client '" + name + "' is mode=async with a THREAD_POOL bulkhead. There "
                        + "is no calling thread to release, so it would add a thread hop and bound "
                        + "nothing. Use SEMAPHORE.");
            }
            if (Boolean.TRUE.equals(resilience.getBulkhead().getEnabled())
                    && !resilience.getBulkhead().getMaxWaitDuration().isZero()) {
                problems.add("client '" + name + "' is mode=async with bulkhead.max-wait-duration="
                        + resilience.getBulkhead().getMaxWaitDuration() + ". Waiting for a permit "
                        + "would park an event-loop thread, so the reactive pipeline never waits. "
                        + "Set it to 0.");
            }
            return;
        }
        if (Boolean.TRUE.equals(resilience.getTimeLimiter().getEnabled())) {
            problems.add("client '" + name + "' is mode=sync with resilience.time-limiter.enabled. "
                    + "A blocking call cannot be interrupted by a deadline without a second thread; "
                    + "use request-timeout, which the transport enforces.");
        }
    }

    private void checkTls(String name, ClientProperties merged, List<String> problems) {
        TlsProperties tls = merged.getTls();
        boolean insecure = Boolean.TRUE.equals(tls.getTrustAll())
                || Boolean.FALSE.equals(tls.getHostnameVerification());
        if (!insecure) {
            return;
        }
        if (!properties.isAllowTrustAll()) {
            problems.add("client '" + name + "' disables certificate or hostname verification, but "
                    + RestClientProperties.PREFIX + ".allow-trust-all is not true. Two switches are "
                    + "required so that a development YAML cannot be copied into a deployment and "
                    + "keep working.");
        }
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        List<String> forbidden = properties.getProductionProfiles().stream()
                .filter(active::contains)
                .toList();
        if (!forbidden.isEmpty()) {
            problems.add("client '" + name + "' disables certificate or hostname verification while "
                    + "profile(s) " + forbidden + " are active. TLS verification is not optional in "
                    + "production; if this environment is not production, rename the profile or "
                    + "adjust " + RestClientProperties.PREFIX + ".production-profiles.");
        }
        if (tls.getKeystorePath() != null && tls.getKeystorePassword() == null) {
            problems.add("client '" + name + "' has tls.keystore-path but no tls.keystore-password.");
        }
    }

    private void checkAuth(String name, ClientProperties merged, List<String> problems,
                           List<String> warnings) {
        AuthProperties auth = merged.getAuth();
        String type = auth.getType() == null ? AuthTypes.NONE : auth.getType().toLowerCase(Locale.ROOT);
        if (!authenticators.knownTypes().contains(type)) {
            problems.add("client '" + name + "': unknown auth.type '" + type + "'. Known types: "
                    + String.join(", ", authenticators.knownTypes()) + ". Add one by publishing a "
                    + "ClientAuthenticationProvider bean.");
            return;
        }
        if (AuthTypes.OAUTH2_TOKEN_RELAY.equals(type)) {
            checkTokenRelay(name, auth, problems);
        }
        if (AuthTypes.API_KEY.equals(type) && auth.getQueryParamName() != null) {
            warnings.add("client '" + name + "' sends its API key as query parameter '"
                    + auth.getQueryParamName() + "'. The credential will appear in the access log of "
                    + "every proxy on the path. Use a header if the partner allows one.");
        }
        if (AuthTypes.BEARER.equals(type) && auth.getToken() != null
                && auth.getTokenSupplier() != null) {
            problems.add("client '" + name + "': auth.token and auth.token-supplier are both set. "
                    + "Exactly one of them can be in effect, and guessing which would be worse than "
                    + "refusing.");
        }
    }

    private void checkTokenRelay(String name, AuthProperties auth, List<String> problems) {
        if (!Boolean.TRUE.equals(auth.getRelayEnabled())) {
            problems.add("client '" + name + "': auth.type=oauth2-token-relay requires "
                    + "auth.relay-enabled: true on this client. Relaying a user's credential to a "
                    + "third party is a per-dependency decision and is never inherited.");
        }
        boolean resourceServer = environment.containsProperty(
                "spring.security.oauth2.resourceserver.jwt.issuer-uri")
                || environment.containsProperty(
                        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri")
                || environment.containsProperty(
                        "spring.security.oauth2.resourceserver.opaquetoken.introspection-uri");
        if (!resourceServer) {
            problems.add("client '" + name + "' relays an inbound token, but this service is not "
                    + "configured as a resource server, so there will never be one to relay and "
                    + "every call would fail closed. Configure "
                    + "spring.security.oauth2.resourceserver, or use a different auth.type.");
        }
    }

    private void checkResilience(String name, ClientProperties merged, List<String> problems,
                                 List<String> warnings) {
        ResilienceProperties resilience = merged.getResilience();
        CircuitBreakerProperties breaker = resilience.getCircuitBreaker();
        if (Boolean.TRUE.equals(breaker.getEnabled())
                && breaker.getMinimumNumberOfCalls() > breaker.getSlidingWindowSize()) {
            problems.add("client '" + name + "': circuit-breaker.minimum-number-of-calls ("
                    + breaker.getMinimumNumberOfCalls() + ") exceeds sliding-window-size ("
                    + breaker.getSlidingWindowSize() + "), so the failure rate is never evaluated "
                    + "and the breaker can never open.");
        }
        if (Boolean.TRUE.equals(breaker.getEnabled())
                && breaker.getRecordFailureOnStatus().stream().anyMatch(this::isClientError)) {
            warnings.add("client '" + name + "' records 4xx statuses as circuit-breaker failures. A "
                    + "client error means this service sent something the peer rejected, so the "
                    + "breaker will open on a bug here and take a healthy dependency out of service.");
        }
        RateLimiterProperties limiter = resilience.getRateLimiter();
        if (Boolean.TRUE.equals(limiter.getEnabled())
                && limiter.getTimeoutDuration().compareTo(merged.getReadTimeout()) > 0) {
            problems.add("client '" + name + "': rate-limiter.timeout-duration ("
                    + limiter.getTimeoutDuration() + ") exceeds read-timeout (" + merged.getReadTimeout()
                    + "). A caller could wait longer for a permit than it is willing to wait for the "
                    + "whole call.");
        }
        if (Boolean.TRUE.equals(resilience.getRetry().getEnabled())
                && resilience.getRetry().getMaxAttempts() > 1
                && !Boolean.TRUE.equals(resilience.getRetry().getIdempotentMethodsOnly())) {
            warnings.add("client '" + name + "' retries non-idempotent methods by default. A read "
                    + "timeout on a POST says nothing about whether the peer processed it; prefer "
                    + "per-request opt-in with the X-Ludwig-Retry header.");
        }
    }

    private boolean isClientError(int status) {
        // CHECKSTYLE.OFF: MagicNumber - the 4xx range is the definition, not a tunable.
        return status >= 400 && status < 500 && status != 408 && status != 429;
        // CHECKSTYLE.ON: MagicNumber
    }
}
