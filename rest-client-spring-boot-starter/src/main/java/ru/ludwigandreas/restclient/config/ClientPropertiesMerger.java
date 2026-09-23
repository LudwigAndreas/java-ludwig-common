package ru.ludwigandreas.restclient.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns three layers of configuration into the one {@link ClientProperties} a client actually runs
 * with.
 *
 * <h2>The layers, lowest first</h2>
 *
 * <ol>
 *   <li><strong>Built-in defaults</strong> - {@link #builtInDefaults()}. The only place the
 *       starter's opinions are written down, so "what is the default read timeout" is answered by
 *       reading one method rather than by grepping for {@code getOrDefault} calls.</li>
 *   <li><strong>The {@code defaults} block</strong> - what this deployment wants for every client.</li>
 *   <li><strong>The client's own block</strong> - what this dependency needs.</li>
 * </ol>
 *
 * <p>Two more layers follow, outside this class: customizer beans, then per-request overrides. The
 * README states the full order; this class implements the first three of it.
 *
 * <h2>The merge rules, and why they are not uniform</h2>
 *
 * <ul>
 *   <li><strong>Scalars</strong>: the higher layer wins when it is non-null. This is why every
 *       scalar in the properties tree is a wrapper type - {@code null} is the only way to say "not
 *       mentioned here", and a primitive {@code 0} would be indistinguishable from a deliberate
 *       zero.</li>
 *   <li><strong>Nested blocks</strong>: merged recursively, field by field. A client that sets
 *       {@code resilience.retry.max-attempts} keeps the defaults block's backoff multiplier.</li>
 *   <li><strong>Lists</strong>: <em>replaced</em>, never concatenated. A client that writes
 *       {@code retry-on-status: [503]} means 503 and nothing else; silently appending the six
 *       statuses the defaults block listed would be the starter overruling an explicit decision.
 *       Where "the defaults plus mine" is genuinely what people want - redaction lists - there is a
 *       separate {@code additional-*} key that <em>is</em> concatenated, so the two intentions are
 *       expressible and distinguishable.</li>
 *   <li><strong>Maps</strong>: merged key by key, higher layer winning per key. {@code
 *       default-headers} is the only map here, and "the platform's headers plus mine" is the only
 *       thing it ever means.</li>
 *   <li><strong>Two exceptions that never inherit</strong>: {@code base-url}, which is meaningless
 *       as a shared value, and {@code auth.relay-enabled}, because propagating a user's token must
 *       be a decision taken per dependency and never something nine clients picked up from a
 *       defaults block nobody re-read.</li>
 * </ul>
 */
public final class ClientPropertiesMerger {

    /** Statuses retried by default: the ones that mean "not now" rather than "not ever". */
    private static final List<Integer> DEFAULT_RETRY_STATUSES = List.of(408, 425, 429, 500, 502, 503, 504);

    /**
     * Statuses that open the breaker by default. Deliberately no 4xx - see
     * {@link CircuitBreakerProperties#getRecordFailureOnStatus()}.
     */
    private static final List<Integer> DEFAULT_FAILURE_STATUSES = List.of(408, 429, 500, 502, 503, 504);

    /** Headers whose value is replaced with {@code ****} wherever this module writes them. */
    private static final List<String> DEFAULT_REDACTED_HEADERS = List.of(
            "Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie",
            "X-Api-Key", "X-Auth-Token", "Api-Key");

    /** JSON field names redacted inside a logged body, at any depth. */
    private static final List<String> DEFAULT_REDACTED_FIELDS = List.of(
            "password", "secret", "token", "access_token", "refresh_token", "id_token",
            "client_secret", "pin", "otp", "card_number", "cvv");

    private static final List<String> DEFAULT_TLS_PROTOCOLS = List.of("TLSv1.3", "TLSv1.2");

    private ClientPropertiesMerger() {
    }

    /**
     * The starter's own opinions, as a fully-populated {@link ClientProperties}.
     *
     * <p>Everything except {@code base-url} has a value here, which is what lets the rest of the
     * module treat a merged {@code ClientProperties} as non-null and stop writing null checks on
     * the request path.
     */
    public static ClientProperties builtInDefaults() {
        // CHECKSTYLE.OFF: MagicNumber - this method and the five below ARE the table of default
        // values; naming each number as a constant would move it one line up and tell no one anything.
        ClientProperties p = new ClientProperties();
        p.setMode(ClientMode.SYNC);
        p.setTransport(TransportEngine.HTTP_CLIENT);
        p.setConnectTimeout(Duration.ofSeconds(2));
        p.setReadTimeout(Duration.ofSeconds(10));
        p.setConnectionRequestTimeout(Duration.ofSeconds(2));
        p.setRedirects(RedirectPolicy.NORMAL);
        p.setCompression(true);
        p.setHttp2(false);
        applyPoolDefaults(p.getPool());
        applyTlsDefaults(p.getTls());
        applyAuthDefaults(p.getAuth());
        applyResilienceDefaults(p.getResilience());
        applyLoggingDefaults(p.getLogging());
        applyAuditDefaults(p.getAudit());
        applySerializationDefaults(p.getSerialization());
        return p;
        // CHECKSTYLE.ON: MagicNumber
    }

    private static void applyPoolDefaults(PoolProperties pool) {
        // CHECKSTYLE.OFF: MagicNumber - see builtInDefaults().
        pool.setMaxTotal(50);
        pool.setMaxPerRoute(20);
        pool.setIdleEviction(Duration.ofSeconds(30));
        pool.setTimeToLive(Duration.ofMinutes(5));
        pool.setValidateAfterInactivity(Duration.ofSeconds(2));
        pool.setKeepAlive(Duration.ofMinutes(1));
        // CHECKSTYLE.ON: MagicNumber
    }

    private static void applyTlsDefaults(TlsProperties tls) {
        tls.setTruststoreType("PKCS12");
        tls.setKeystoreType("PKCS12");
        tls.setProtocols(DEFAULT_TLS_PROTOCOLS);
        tls.setHostnameVerification(true);
        tls.setTrustAll(false);
    }

    private static void applyAuthDefaults(AuthProperties auth) {
        // CHECKSTYLE.OFF: MagicNumber - see builtInDefaults().
        auth.setType(AuthTypes.NONE);
        auth.setScheme("Bearer");
        auth.setHeaderName("X-Api-Key");
        auth.setRefreshSkew(Duration.ofSeconds(30));
        auth.setFailWhenNoToken(true);
        // relay-enabled is deliberately left null here as well as in the defaults block: it must be
        // stated by the client that relays, and nowhere else.
        // CHECKSTYLE.ON: MagicNumber
    }

    private static void applyResilienceDefaults(ResilienceProperties resilience) {
        // CHECKSTYLE.OFF: MagicNumber - see builtInDefaults().
        resilience.setEnabled(true);

        RetryProperties retry = resilience.getRetry();
        retry.setEnabled(true);
        retry.setMaxAttempts(3);
        retry.setWaitDuration(Duration.ofMillis(200));
        retry.setExponentialBackoffMultiplier(2.0);
        retry.setRandomizedWaitFactor(0.5);
        retry.setRetryOnStatus(DEFAULT_RETRY_STATUSES);
        retry.setRetryOnException(List.of());
        retry.setIdempotentMethodsOnly(true);
        retry.setRespectRetryAfter(true);
        retry.setMaxRetryAfter(Duration.ofSeconds(30));

        CircuitBreakerProperties breaker = resilience.getCircuitBreaker();
        breaker.setEnabled(true);
        breaker.setFailureRateThreshold(50f);
        breaker.setSlowCallRateThreshold(100f);
        breaker.setSlidingWindowType("COUNT_BASED");
        breaker.setSlidingWindowSize(100);
        breaker.setMinimumNumberOfCalls(10);
        breaker.setWaitDurationInOpenState(Duration.ofSeconds(30));
        breaker.setPermittedNumberOfCallsInHalfOpenState(5);
        breaker.setAutomaticTransitionFromOpenToHalfOpen(true);
        breaker.setRecordFailureOnStatus(DEFAULT_FAILURE_STATUSES);
        breaker.setRecordFailureOnException(List.of());

        BulkheadProperties bulkhead = resilience.getBulkhead();
        bulkhead.setEnabled(false);
        bulkhead.setType(BulkheadType.SEMAPHORE);
        bulkhead.setMaxConcurrentCalls(25);
        bulkhead.setMaxWaitDuration(Duration.ZERO);
        bulkhead.setQueueCapacity(0);

        RateLimiterProperties limiter = resilience.getRateLimiter();
        limiter.setEnabled(false);
        limiter.setLimitForPeriod(100);
        limiter.setLimitRefreshPeriod(Duration.ofSeconds(1));
        limiter.setTimeoutDuration(Duration.ZERO);

        TimeLimiterProperties timeLimiter = resilience.getTimeLimiter();
        timeLimiter.setEnabled(false);
        timeLimiter.setCancelRunningFuture(true);
        // CHECKSTYLE.ON: MagicNumber
    }

    private static void applyLoggingDefaults(LoggingProperties logging) {
        // CHECKSTYLE.OFF: MagicNumber - see builtInDefaults().
        logging.setLevel(LogDetail.BASIC);
        logging.setMaxBodySize(2048);
        logging.setRedactedHeaders(DEFAULT_REDACTED_HEADERS);
        logging.setAdditionalRedactedHeaders(List.of());
        logging.setRedactedFields(DEFAULT_REDACTED_FIELDS);
        logging.setAdditionalRedactedFields(List.of());
        logging.setLogRequestBeforeCall(false);
        // CHECKSTYLE.ON: MagicNumber
    }

    private static void applyAuditDefaults(AuditProperties audit) {
        audit.setEnabled(false);
        audit.setSamplingProbability(1.0);
        audit.setIncludeRequestHeaders(List.of());
        audit.setIncludeResponseHeaders(List.of());
    }

    private static void applySerializationDefaults(SerializationProperties serialization) {
        serialization.setFailOnUnknownProperties(false);
        serialization.setExcludeNulls(true);
        serialization.setModules(List.of());
    }

    /**
     * The effective configuration of one named client.
     *
     * @param defaults the {@code defaults} block; {@code null} is treated as empty
     * @param client   the client's own block; {@code null} is treated as empty
     */
    public static ClientProperties resolve(ClientProperties defaults, ClientProperties client) {
        ClientProperties merged = merge(builtInDefaults(), defaults == null ? new ClientProperties() : defaults);
        merged = merge(merged, client == null ? new ClientProperties() : client);
        // base-url is never inherited: a shared base URL is wrong for every client but one, so
        // whatever the lower layers said, only the client's own value counts.
        merged.setBaseUrl(client == null ? null : client.getBaseUrl());
        deriveContextualDefaults(merged);
        enableWhatWasConfigured(merged, defaults, client);
        return merged;
    }

    /**
     * Switches on a policy whose settings somebody took the trouble to write.
     *
     * <p>The bulkhead and the rate limiter are off by default, because neither has a value that can
     * be guessed for an unknown dependency. But writing
     *
     * <pre>{@code
     * resilience:
     *   bulkhead: { max-concurrent-calls: 20 }
     * }</pre>
     *
     * <p>and getting no bulkhead is the kind of silent no-op that costs an afternoon: the
     * configuration looks right, the metric is absent, and nothing says why. A declared setting is
     * read as intent to use the policy - unless {@code enabled} was stated explicitly, in which case
     * the explicit statement wins in both directions, so {@code enabled: false} still disables a
     * fully configured block.
     */
    private static void enableWhatWasConfigured(ClientProperties merged, ClientProperties defaults,
                                                ClientProperties client) {
        BulkheadProperties bulkhead = merged.getResilience().getBulkhead();
        if (!statedEnabled(defaults, client, ResilienceProperties::getBulkhead,
                BulkheadProperties::getEnabled)
                && (configuredBulkhead(defaults) || configuredBulkhead(client))) {
            bulkhead.setEnabled(true);
        }
        RateLimiterProperties limiter = merged.getResilience().getRateLimiter();
        if (!statedEnabled(defaults, client, ResilienceProperties::getRateLimiter,
                RateLimiterProperties::getEnabled)
                && (configuredRateLimiter(defaults) || configuredRateLimiter(client))) {
            limiter.setEnabled(true);
        }
    }

    private static <P> boolean statedEnabled(ClientProperties defaults, ClientProperties client,
                                             java.util.function.Function<ResilienceProperties, P> block,
                                             java.util.function.Function<P, Boolean> enabled) {
        return stated(defaults, block, enabled) || stated(client, block, enabled);
    }

    private static <P> boolean stated(ClientProperties source,
                                      java.util.function.Function<ResilienceProperties, P> block,
                                      java.util.function.Function<P, Boolean> enabled) {
        return source != null && enabled.apply(block.apply(source.getResilience())) != null;
    }

    private static boolean configuredBulkhead(ClientProperties source) {
        if (source == null) {
            return false;
        }
        BulkheadProperties bulkhead = source.getResilience().getBulkhead();
        return bulkhead.getMaxConcurrentCalls() != null || bulkhead.getType() != null
                || bulkhead.getMaxWaitDuration() != null || bulkhead.getCoreThreadPoolSize() != null
                || bulkhead.getQueueCapacity() != null;
    }

    private static boolean configuredRateLimiter(ClientProperties source) {
        if (source == null) {
            return false;
        }
        RateLimiterProperties limiter = source.getResilience().getRateLimiter();
        return limiter.getLimitForPeriod() != null || limiter.getLimitRefreshPeriod() != null
                || limiter.getTimeoutDuration() != null;
    }

    /** Layers {@code overlay} on top of {@code base} and returns a new instance; neither is changed. */
    public static ClientProperties merge(ClientProperties base, ClientProperties overlay) {
        ClientProperties out = new ClientProperties();
        out.setBaseUrl(pick(overlay.getBaseUrl(), base.getBaseUrl()));
        out.setMode(pick(overlay.getMode(), base.getMode()));
        out.setTransport(pick(overlay.getTransport(), base.getTransport()));
        out.setConnectTimeout(pick(overlay.getConnectTimeout(), base.getConnectTimeout()));
        out.setReadTimeout(pick(overlay.getReadTimeout(), base.getReadTimeout()));
        out.setConnectionRequestTimeout(
                pick(overlay.getConnectionRequestTimeout(), base.getConnectionRequestTimeout()));
        out.setRequestTimeout(pick(overlay.getRequestTimeout(), base.getRequestTimeout()));
        out.setRedirects(pick(overlay.getRedirects(), base.getRedirects()));
        out.setCompression(pick(overlay.getCompression(), base.getCompression()));
        out.setHttp2(pick(overlay.getHttp2(), base.getHttp2()));
        out.setUserAgent(pick(overlay.getUserAgent(), base.getUserAgent()));
        out.setDefaultHeaders(mergeMap(base.getDefaultHeaders(), overlay.getDefaultHeaders()));
        out.setDefaultQueryParams(mergeMap(base.getDefaultQueryParams(), overlay.getDefaultQueryParams()));
        mergePool(out.getPool(), base.getPool(), overlay.getPool());
        mergeTls(out.getTls(), base.getTls(), overlay.getTls());
        mergeProxy(out.getProxy(), base.getProxy(), overlay.getProxy());
        mergeAuth(out.getAuth(), base.getAuth(), overlay.getAuth());
        mergeResilience(out.getResilience(), base.getResilience(), overlay.getResilience());
        mergeLogging(out.getLogging(), base.getLogging(), overlay.getLogging());
        mergeAudit(out.getAudit(), base.getAudit(), overlay.getAudit());
        mergeSerialization(out.getSerialization(), base.getSerialization(), overlay.getSerialization());
        return out;
    }

    private static void mergePool(PoolProperties out, PoolProperties base, PoolProperties overlay) {
        out.setMaxTotal(pick(overlay.getMaxTotal(), base.getMaxTotal()));
        out.setMaxPerRoute(pick(overlay.getMaxPerRoute(), base.getMaxPerRoute()));
        out.setIdleEviction(pick(overlay.getIdleEviction(), base.getIdleEviction()));
        out.setTimeToLive(pick(overlay.getTimeToLive(), base.getTimeToLive()));
        out.setValidateAfterInactivity(
                pick(overlay.getValidateAfterInactivity(), base.getValidateAfterInactivity()));
        out.setKeepAlive(pick(overlay.getKeepAlive(), base.getKeepAlive()));
    }

    private static void mergeTls(TlsProperties out, TlsProperties base, TlsProperties overlay) {
        out.setTruststorePath(pick(overlay.getTruststorePath(), base.getTruststorePath()));
        out.setTruststorePassword(pick(overlay.getTruststorePassword(), base.getTruststorePassword()));
        out.setTruststoreType(pick(overlay.getTruststoreType(), base.getTruststoreType()));
        out.setKeystorePath(pick(overlay.getKeystorePath(), base.getKeystorePath()));
        out.setKeystorePassword(pick(overlay.getKeystorePassword(), base.getKeystorePassword()));
        out.setKeyPassword(pick(overlay.getKeyPassword(), base.getKeyPassword()));
        out.setKeystoreType(pick(overlay.getKeystoreType(), base.getKeystoreType()));
        out.setProtocols(pickList(overlay.getProtocols(), base.getProtocols()));
        out.setCipherSuites(pickList(overlay.getCipherSuites(), base.getCipherSuites()));
        out.setHostnameVerification(pick(overlay.getHostnameVerification(), base.getHostnameVerification()));
        out.setTrustAll(pick(overlay.getTrustAll(), base.getTrustAll()));
    }

    private static void mergeProxy(ProxyProperties out, ProxyProperties base, ProxyProperties overlay) {
        out.setHost(pick(overlay.getHost(), base.getHost()));
        out.setPort(pick(overlay.getPort(), base.getPort()));
        out.setNonProxyHosts(pickList(overlay.getNonProxyHosts(), base.getNonProxyHosts()));
        out.setUsername(pick(overlay.getUsername(), base.getUsername()));
        out.setPassword(pick(overlay.getPassword(), base.getPassword()));
    }

    private static void mergeAuth(AuthProperties out, AuthProperties base, AuthProperties overlay) {
        out.setType(pick(overlay.getType(), base.getType()));
        out.setUsername(pick(overlay.getUsername(), base.getUsername()));
        out.setPassword(pick(overlay.getPassword(), base.getPassword()));
        out.setToken(pick(overlay.getToken(), base.getToken()));
        out.setTokenSupplier(pick(overlay.getTokenSupplier(), base.getTokenSupplier()));
        out.setScheme(pick(overlay.getScheme(), base.getScheme()));
        out.setHeaderName(pick(overlay.getHeaderName(), base.getHeaderName()));
        out.setQueryParamName(pick(overlay.getQueryParamName(), base.getQueryParamName()));
        out.setKey(pick(overlay.getKey(), base.getKey()));
        out.setValuePrefix(pick(overlay.getValuePrefix(), base.getValuePrefix()));
        out.setRegistrationId(pick(overlay.getRegistrationId(), base.getRegistrationId()));
        out.setRefreshSkew(pick(overlay.getRefreshSkew(), base.getRefreshSkew()));
        out.setScopes(pickList(overlay.getScopes(), base.getScopes()));
        out.setRetryOnUnauthorized(pick(overlay.getRetryOnUnauthorized(), base.getRetryOnUnauthorized()));
        out.setFailWhenNoToken(pick(overlay.getFailWhenNoToken(), base.getFailWhenNoToken()));
        out.setAuthenticator(pick(overlay.getAuthenticator(), base.getAuthenticator()));
        // NOT inherited: only the overlay's own value counts, so a client relays a user's token
        // because that client says so, never because the defaults block did.
        out.setRelayEnabled(overlay.getRelayEnabled());
    }

    private static void mergeResilience(ResilienceProperties out, ResilienceProperties base,
                                        ResilienceProperties overlay) {
        out.setEnabled(pick(overlay.getEnabled(), base.getEnabled()));
        mergeRetry(out.getRetry(), base.getRetry(), overlay.getRetry());
        mergeCircuitBreaker(out.getCircuitBreaker(), base.getCircuitBreaker(), overlay.getCircuitBreaker());
        mergeBulkhead(out.getBulkhead(), base.getBulkhead(), overlay.getBulkhead());
        mergeRateLimiter(out.getRateLimiter(), base.getRateLimiter(), overlay.getRateLimiter());
        mergeTimeLimiter(out.getTimeLimiter(), base.getTimeLimiter(), overlay.getTimeLimiter());
        FallbackProperties fallback = out.getFallback();
        fallback.setHandler(pick(overlay.getFallback().getHandler(), base.getFallback().getHandler()));
        fallback.setMethods(mergeMap(base.getFallback().getMethods(), overlay.getFallback().getMethods()));
    }

    private static void mergeRetry(RetryProperties out, RetryProperties base, RetryProperties overlay) {
        out.setEnabled(pick(overlay.getEnabled(), base.getEnabled()));
        out.setMaxAttempts(pick(overlay.getMaxAttempts(), base.getMaxAttempts()));
        out.setWaitDuration(pick(overlay.getWaitDuration(), base.getWaitDuration()));
        out.setExponentialBackoffMultiplier(
                pick(overlay.getExponentialBackoffMultiplier(), base.getExponentialBackoffMultiplier()));
        out.setRandomizedWaitFactor(pick(overlay.getRandomizedWaitFactor(), base.getRandomizedWaitFactor()));
        out.setMaxElapsedTime(pick(overlay.getMaxElapsedTime(), base.getMaxElapsedTime()));
        out.setRetryOnStatus(pickList(overlay.getRetryOnStatus(), base.getRetryOnStatus()));
        out.setRetryOnException(pickList(overlay.getRetryOnException(), base.getRetryOnException()));
        out.setIdempotentMethodsOnly(pick(overlay.getIdempotentMethodsOnly(), base.getIdempotentMethodsOnly()));
        out.setRespectRetryAfter(pick(overlay.getRespectRetryAfter(), base.getRespectRetryAfter()));
        out.setMaxRetryAfter(pick(overlay.getMaxRetryAfter(), base.getMaxRetryAfter()));
    }

    private static void mergeCircuitBreaker(CircuitBreakerProperties out, CircuitBreakerProperties base,
                                            CircuitBreakerProperties overlay) {
        out.setEnabled(pick(overlay.getEnabled(), base.getEnabled()));
        out.setFailureRateThreshold(pick(overlay.getFailureRateThreshold(), base.getFailureRateThreshold()));
        out.setSlowCallRateThreshold(pick(overlay.getSlowCallRateThreshold(), base.getSlowCallRateThreshold()));
        out.setSlowCallDurationThreshold(
                pick(overlay.getSlowCallDurationThreshold(), base.getSlowCallDurationThreshold()));
        out.setSlidingWindowType(pick(overlay.getSlidingWindowType(), base.getSlidingWindowType()));
        out.setSlidingWindowSize(pick(overlay.getSlidingWindowSize(), base.getSlidingWindowSize()));
        out.setMinimumNumberOfCalls(pick(overlay.getMinimumNumberOfCalls(), base.getMinimumNumberOfCalls()));
        out.setWaitDurationInOpenState(
                pick(overlay.getWaitDurationInOpenState(), base.getWaitDurationInOpenState()));
        out.setPermittedNumberOfCallsInHalfOpenState(pick(
                overlay.getPermittedNumberOfCallsInHalfOpenState(),
                base.getPermittedNumberOfCallsInHalfOpenState()));
        out.setAutomaticTransitionFromOpenToHalfOpen(pick(
                overlay.getAutomaticTransitionFromOpenToHalfOpen(),
                base.getAutomaticTransitionFromOpenToHalfOpen()));
        out.setRecordFailureOnStatus(
                pickList(overlay.getRecordFailureOnStatus(), base.getRecordFailureOnStatus()));
        out.setRecordFailureOnException(
                pickList(overlay.getRecordFailureOnException(), base.getRecordFailureOnException()));
    }

    private static void mergeBulkhead(BulkheadProperties out, BulkheadProperties base,
                                      BulkheadProperties overlay) {
        out.setEnabled(pick(overlay.getEnabled(), base.getEnabled()));
        out.setType(pick(overlay.getType(), base.getType()));
        out.setMaxConcurrentCalls(pick(overlay.getMaxConcurrentCalls(), base.getMaxConcurrentCalls()));
        out.setMaxWaitDuration(pick(overlay.getMaxWaitDuration(), base.getMaxWaitDuration()));
        out.setCoreThreadPoolSize(pick(overlay.getCoreThreadPoolSize(), base.getCoreThreadPoolSize()));
        out.setQueueCapacity(pick(overlay.getQueueCapacity(), base.getQueueCapacity()));
    }

    private static void mergeRateLimiter(RateLimiterProperties out, RateLimiterProperties base,
                                         RateLimiterProperties overlay) {
        out.setEnabled(pick(overlay.getEnabled(), base.getEnabled()));
        out.setLimitForPeriod(pick(overlay.getLimitForPeriod(), base.getLimitForPeriod()));
        out.setLimitRefreshPeriod(pick(overlay.getLimitRefreshPeriod(), base.getLimitRefreshPeriod()));
        out.setTimeoutDuration(pick(overlay.getTimeoutDuration(), base.getTimeoutDuration()));
    }

    private static void mergeTimeLimiter(TimeLimiterProperties out, TimeLimiterProperties base,
                                         TimeLimiterProperties overlay) {
        out.setEnabled(pick(overlay.getEnabled(), base.getEnabled()));
        out.setTimeout(pick(overlay.getTimeout(), base.getTimeout()));
        out.setCancelRunningFuture(pick(overlay.getCancelRunningFuture(), base.getCancelRunningFuture()));
    }

    private static void mergeLogging(LoggingProperties out, LoggingProperties base, LoggingProperties overlay) {
        out.setLevel(pick(overlay.getLevel(), base.getLevel()));
        out.setMaxBodySize(pick(overlay.getMaxBodySize(), base.getMaxBodySize()));
        out.setRedactedHeaders(pickList(overlay.getRedactedHeaders(), base.getRedactedHeaders()));
        out.setRedactedFields(pickList(overlay.getRedactedFields(), base.getRedactedFields()));
        // The `additional-*` keys are the exception to "lists are replaced": their whole purpose is
        // to be additive, so every layer's contribution is kept.
        out.setAdditionalRedactedHeaders(
                concat(base.getAdditionalRedactedHeaders(), overlay.getAdditionalRedactedHeaders()));
        out.setAdditionalRedactedFields(
                concat(base.getAdditionalRedactedFields(), overlay.getAdditionalRedactedFields()));
        out.setLogRequestBeforeCall(pick(overlay.getLogRequestBeforeCall(), base.getLogRequestBeforeCall()));
    }

    private static void mergeAudit(AuditProperties out, AuditProperties base, AuditProperties overlay) {
        out.setEnabled(pick(overlay.getEnabled(), base.getEnabled()));
        out.setSamplingProbability(pick(overlay.getSamplingProbability(), base.getSamplingProbability()));
        out.setEmitter(pick(overlay.getEmitter(), base.getEmitter()));
        out.setIncludeRequestHeaders(
                pickList(overlay.getIncludeRequestHeaders(), base.getIncludeRequestHeaders()));
        out.setIncludeResponseHeaders(
                pickList(overlay.getIncludeResponseHeaders(), base.getIncludeResponseHeaders()));
    }

    private static void mergeSerialization(SerializationProperties out, SerializationProperties base,
                                           SerializationProperties overlay) {
        out.setPropertyNamingStrategy(
                pick(overlay.getPropertyNamingStrategy(), base.getPropertyNamingStrategy()));
        out.setDateFormat(pick(overlay.getDateFormat(), base.getDateFormat()));
        out.setTimeZone(pick(overlay.getTimeZone(), base.getTimeZone()));
        out.setFailOnUnknownProperties(
                pick(overlay.getFailOnUnknownProperties(), base.getFailOnUnknownProperties()));
        out.setExcludeNulls(pick(overlay.getExcludeNulls(), base.getExcludeNulls()));
        out.setModules(pickList(overlay.getModules(), base.getModules()));
    }

    /**
     * Fills the defaults that can only be computed once the rest of the client is known.
     *
     * <p>They are expressed as relationships rather than numbers because a fixed number would be
     * wrong for one of the two clients in any realistic deployment: a slow-call threshold of one
     * second is meaningless next to a 120-second billing call, and a time limiter shorter than the
     * read timeout would cancel healthy calls.
     */
    private static void deriveContextualDefaults(ClientProperties merged) {
        if (merged.getMode() == ClientMode.ASYNC) {
            // Reactor Netty is the only engine WebClient runs on here, so the transport key is not a
            // free choice for an async client - it is a consequence of the mode.
            merged.setTransport(TransportEngine.REACTOR_NETTY);
        }
        CircuitBreakerProperties breaker = merged.getResilience().getCircuitBreaker();
        if (breaker.getSlowCallDurationThreshold() == null) {
            breaker.setSlowCallDurationThreshold(merged.getReadTimeout());
        }
        RetryProperties retry = merged.getResilience().getRetry();
        if (retry.getMaxElapsedTime() == null) {
            retry.setMaxElapsedTime(defaultRetryBudget(merged, retry));
        }
        TimeLimiterProperties timeLimiter = merged.getResilience().getTimeLimiter();
        if (timeLimiter.getTimeout() == null) {
            timeLimiter.setTimeout(merged.getRequestTimeout() != null
                    ? merged.getRequestTimeout() : merged.getReadTimeout());
        }
        BulkheadProperties bulkhead = merged.getResilience().getBulkhead();
        if (bulkhead.getCoreThreadPoolSize() == null) {
            bulkhead.setCoreThreadPoolSize(bulkhead.getMaxConcurrentCalls());
        }
        AuthProperties auth = merged.getAuth();
        if (auth.getRetryOnUnauthorized() == null) {
            // Refreshable credentials are worth re-minting after a 401; a static one is not, and
            // re-sending the same wrong key only doubles the load on a peer already saying no.
            auth.setRetryOnUnauthorized(AuthTypes.isRefreshable(auth.getType()));
        }
    }

    /**
     * The retry budget a client gets when it does not state one.
     *
     * <p>Derived rather than a fixed number, because a fixed one is wrong for one of the two clients
     * in any realistic deployment: 30 seconds is generous for an 800-millisecond pricing call and
     * makes a three-attempt policy on a 120-second billing call a lie - the second attempt could
     * never start. The derived value is what the configuration already implies: every attempt at its
     * full timeout, plus the waits between them at their jittered maximum.
     *
     * <p>Stating the budget explicitly is still the better choice for a client called from a request
     * thread, because it is the only setting that bounds how long that thread is held.
     */
    private static Duration defaultRetryBudget(ClientProperties merged, RetryProperties retry) {
        Duration oneAttempt = merged.getRequestTimeout() != null
                ? merged.getRequestTimeout() : merged.getReadTimeout();
        double wait = retry.getWaitDuration().toMillis();
        double multiplier = retry.getExponentialBackoffMultiplier();
        double jitterCeiling = 1.0 + retry.getRandomizedWaitFactor();
        double totalWaitMillis = 0;
        for (int attempt = 1; attempt < retry.getMaxAttempts(); attempt++) {
            totalWaitMillis += wait * Math.pow(multiplier, attempt - 1) * jitterCeiling;
        }
        return oneAttempt.multipliedBy(retry.getMaxAttempts())
                .plus(Duration.ofMillis((long) Math.ceil(totalWaitMillis)));
    }

    private static <T> T pick(T overlay, T base) {
        return overlay != null ? overlay : base;
    }

    private static <T> List<T> pickList(List<T> overlay, List<T> base) {
        return overlay != null ? List.copyOf(overlay) : base;
    }

    private static <T> List<T> concat(List<T> base, List<T> overlay) {
        List<T> out = new ArrayList<>();
        if (base != null) {
            out.addAll(base);
        }
        if (overlay != null) {
            out.addAll(overlay);
        }
        return List.copyOf(out);
    }

    private static <K, V> Map<K, V> mergeMap(Map<K, V> base, Map<K, V> overlay) {
        Map<K, V> out = new LinkedHashMap<>();
        if (base != null) {
            out.putAll(base);
        }
        if (overlay != null) {
            out.putAll(overlay);
        }
        return out;
    }
}
