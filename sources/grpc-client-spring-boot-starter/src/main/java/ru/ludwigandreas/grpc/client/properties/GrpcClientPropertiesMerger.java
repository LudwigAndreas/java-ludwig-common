package ru.ludwigandreas.grpc.client.properties;

import java.beans.PropertyDescriptor;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.PropertyAccessorFactory;
import org.springframework.util.unit.DataSize;

/**
 * Resolves one client's effective settings: built-in, then {@code defaults}, then the client.
 *
 * <p>The rules are {@code rest-client}'s, restated here:
 * <ul>
 *   <li>a scalar overrides when it is set;</li>
 *   <li>a nested block merges field by field, so a client that sets one field of an inherited
 *       block keeps the rest;</li>
 *   <li>a list or a map is a value and replaces, never appends;</li>
 *   <li>{@code target} and {@code auth.relay-enabled} are never inherited from {@code defaults} -
 *       a shared target is a wrong target, and relaying a caller's token is a decision per
 *       dependency;</li>
 *   <li>a {@code bulkhead} or {@code rate-limiter} block that is written anywhere enables that
 *       policy; the built-in layer has neither.</li>
 * </ul>
 *
 * <p>The merge is generic over the property tree rather than written field by field, so a key
 * added to {@link ClientSettings} is merged by these rules without a line here.
 */
public final class GrpcClientPropertiesMerger {

    private static final String BLOCK_PACKAGE = ClientSettings.class.getPackageName();
    private static final Duration DEFAULT_DEADLINE = Duration.ofSeconds(5);
    private static final int DEFAULT_MESSAGE_MEGABYTES = 4;
    private static final int KEEPALIVE_SECONDS = 30;
    private static final int KEEPALIVE_TIMEOUT_SECONDS = 20;
    private static final int IDLE_MINUTES = 30;
    private static final float FAILURE_RATE = 50f;
    private static final int WINDOW = 100;
    private static final int MINIMUM_CALLS = 20;
    private static final int BULKHEAD_CALLS = 25;
    private static final int OPEN_SECONDS = 30;
    private static final int ATTEMPTS = 3;
    private static final int INITIAL_BACKOFF_MILLIS = 100;
    private static final int MAX_BACKOFF_SECONDS = 2;
    private static final double MULTIPLIER = 2.0;
    private static final int THROTTLING_TOKENS = 10;
    private static final double THROTTLING_RATIO = 0.1;
    private static final int SKEW_SECONDS = 30;
    private static final int PAYLOAD_CHARS = 2048;

    private GrpcClientPropertiesMerger() {
    }

    /**
     * One client's effective settings.
     *
     * @param defaults the {@code defaults} block, possibly {@code null}
     * @param client   the client's own block
     * @return a new, fully populated object; neither argument is modified
     */
    public static ClientSettings resolve(ClientSettings defaults, ClientSettings client) {
        ClientSettings inheritable = merge(null, defaults);
        if (inheritable != null) {
            inheritable.setTarget(null);
            if (inheritable.getAuth() != null) {
                inheritable.getAuth().setRelayEnabled(null);
            }
        }
        return merge(merge(builtIn(), inheritable), client);
    }

    /**
     * The built-in layer: every value a client gets when nothing is written.
     *
     * @return a new object
     */
    public static ClientSettings builtIn() {
        ClientSettings settings = new ClientSettings();
        settings.setDeadline(DEFAULT_DEADLINE);
        settings.setLoadBalancing("round_robin");
        settings.setPlaintext(false);
        settings.setMaxInboundMessageSize(DataSize.ofMegabytes(DEFAULT_MESSAGE_MEGABYTES));
        settings.setIdleTimeout(Duration.ofMinutes(IDLE_MINUTES));

        ClientSettings.Keepalive keepalive = new ClientSettings.Keepalive();
        keepalive.setTime(Duration.ofSeconds(KEEPALIVE_SECONDS));
        keepalive.setTimeout(Duration.ofSeconds(KEEPALIVE_TIMEOUT_SECONDS));
        keepalive.setWithoutCalls(false);
        settings.setKeepalive(keepalive);
        settings.setTls(new ClientSettings.Tls());

        ClientSettings.CircuitBreaker breaker = new ClientSettings.CircuitBreaker();
        breaker.setEnabled(true);
        breaker.setFailureRateThreshold(FAILURE_RATE);
        breaker.setSlidingWindowSize(WINDOW);
        breaker.setMinimumNumberOfCalls(MINIMUM_CALLS);
        breaker.setWaitDurationInOpenState(Duration.ofSeconds(OPEN_SECONDS));
        ClientSettings.Resilience resilience = new ClientSettings.Resilience();
        resilience.setCircuitBreaker(breaker);
        settings.setResilience(resilience);

        ClientSettings.Retry retry = new ClientSettings.Retry();
        retry.setEnabled(true);
        retry.setMaxAttempts(ATTEMPTS);
        retry.setInitialBackoff(Duration.ofMillis(INITIAL_BACKOFF_MILLIS));
        retry.setMaxBackoff(Duration.ofSeconds(MAX_BACKOFF_SECONDS));
        retry.setBackoffMultiplier(MULTIPLIER);
        retry.setRetryOn(List.of("UNAVAILABLE"));
        retry.setThrottlingMaxTokens(THROTTLING_TOKENS);
        retry.setThrottlingTokenRatio(THROTTLING_RATIO);
        settings.setRetry(retry);

        ClientSettings.Auth auth = new ClientSettings.Auth();
        auth.setType("none");
        auth.setRefreshSkew(Duration.ofSeconds(SKEW_SECONDS));
        settings.setAuth(auth);

        ClientSettings.Logging logging = new ClientSettings.Logging();
        logging.setLevel("basic");
        logging.setMaxPayloadChars(PAYLOAD_CHARS);
        settings.setLogging(logging);

        ClientSettings.Audit audit = new ClientSettings.Audit();
        audit.setEnabled(false);
        audit.setSuccessSampling(1.0);
        settings.setAudit(audit);
        return settings;
    }

    /**
     * The policy defaults a declared but partly written block is completed with.
     *
     * @return a rate limiter block with every field set
     */
    public static ClientSettings.RateLimiter rateLimiterDefaults() {
        ClientSettings.RateLimiter limiter = new ClientSettings.RateLimiter();
        limiter.setLimitForPeriod(WINDOW);
        limiter.setRefreshPeriod(Duration.ofSeconds(1));
        limiter.setTimeout(Duration.ZERO);
        return limiter;
    }

    /**
     * The policy defaults a declared but partly written block is completed with.
     *
     * @return a bulkhead block with every field set
     */
    public static ClientSettings.Bulkhead bulkheadDefaults() {
        ClientSettings.Bulkhead bulkhead = new ClientSettings.Bulkhead();
        bulkhead.setMaxConcurrentCalls(BULKHEAD_CALLS);
        bulkhead.setMaxWait(Duration.ZERO);
        return bulkhead;
    }

    /**
     * Merges {@code override} over {@code base} into a new object, by the rules on this class.
     *
     * @param base     the lower layer, or {@code null}
     * @param override the upper layer, or {@code null}
     * @param <T>      a settings type from this package
     * @return a new object, or {@code null} when both are
     */
    @SuppressWarnings("unchecked")
    public static <T> T merge(T base, T override) {
        if (base == null && override == null) {
            return null;
        }
        Class<T> type = (Class<T>) (override != null ? override.getClass() : base.getClass());
        T merged = BeanUtils.instantiateClass(type);
        BeanWrapper out = PropertyAccessorFactory.forBeanPropertyAccess(merged);
        BeanWrapper below = base == null ? null : PropertyAccessorFactory.forBeanPropertyAccess(base);
        BeanWrapper above = override == null ? null : PropertyAccessorFactory.forBeanPropertyAccess(override);
        for (PropertyDescriptor property : out.getPropertyDescriptors()) {
            String name = property.getName();
            if (!out.isWritableProperty(name)) {
                continue;
            }
            Object low = below == null ? null : below.getPropertyValue(name);
            Object high = above == null ? null : above.getPropertyValue(name);
            Object value = isBlock(property.getPropertyType()) ? merge(low, high) : high != null ? high : low;
            out.setPropertyValue(name, value);
        }
        return merged;
    }

    private static boolean isBlock(Class<?> type) {
        return type != null && !type.isEnum() && BLOCK_PACKAGE.equals(type.getPackageName());
    }
}
