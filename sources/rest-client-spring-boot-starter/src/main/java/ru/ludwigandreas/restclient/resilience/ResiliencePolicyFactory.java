package ru.ludwigandreas.restclient.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import ru.ludwigandreas.restclient.config.BulkheadProperties;
import ru.ludwigandreas.restclient.error.RestClientAuthenticationException;
import ru.ludwigandreas.restclient.error.RestClientCallNotPermittedException;
import ru.ludwigandreas.restclient.config.BulkheadType;
import ru.ludwigandreas.restclient.config.CircuitBreakerProperties;
import ru.ludwigandreas.restclient.config.ClientMode;
import ru.ludwigandreas.restclient.config.ClientProperties;
import ru.ludwigandreas.restclient.config.RateLimiterProperties;
import ru.ludwigandreas.restclient.config.RetryProperties;
import ru.ludwigandreas.restclient.config.ResilienceProperties;
import ru.ludwigandreas.restclient.config.TimeLimiterProperties;

/**
 * Builds one client's {@link ClientResiliencePolicy} from its properties, registering each entry in
 * the shared registry under the client's own name.
 *
 * <p>Naming every entry after the client is what makes the Resilience4j metrics joinable with this
 * module's: {@code resilience4j.circuitbreaker.state{name="billing"}} and
 * {@code ludwig.restclient.requests{client="billing"}} describe the same dependency, and a
 * dashboard can put them on one row.
 */
public class ResiliencePolicyFactory {

    private final ResilienceRegistries registries;
    private final Clock clock;
    private final ClassLoader classLoader;
    /** Creates the factory over the shared, Micrometer-bound Resilience4j registries. */
    public ResiliencePolicyFactory(ResilienceRegistries registries, Clock clock, ClassLoader classLoader) {
        this.registries = registries;
        this.clock = clock;
        this.classLoader = classLoader;
    }

    /** The policy for {@code clientName}, from its already-merged properties. */
    public ClientResiliencePolicy create(String clientName, ClientProperties props) {
        ResilienceProperties resilience = props.getResilience();
        boolean on = Boolean.TRUE.equals(resilience.getEnabled());
        CircuitBreakerProperties breakerProps = resilience.getCircuitBreaker();
        // Computed before the breaker, because the breaker's own predicates close over them: the
        // pipeline hands it an AttemptOutcome rather than an exception for a response, so "is this a
        // failure" has to be answerable from the configuration, not from a thrown type.
        Set<Integer> failureStatuses = Set.copyOf(breakerProps.getRecordFailureOnStatus());
        List<Class<? extends Throwable>> failureExceptions = resolve(clientName,
                "circuit-breaker.record-failure-on-exception", breakerProps.getRecordFailureOnException());
        return new ClientResiliencePolicy(
                clientName,
                on ? rateLimiter(clientName, resilience.getRateLimiter()) : null,
                on ? semaphoreBulkhead(clientName, resilience.getBulkhead()) : null,
                on ? threadPoolBulkhead(clientName, resilience.getBulkhead()) : null,
                on ? circuitBreaker(clientName, breakerProps, failureStatuses, failureExceptions) : null,
                retryPolicy(clientName, props),
                on ? timeLimiter(clientName, props.getMode(), resilience.getTimeLimiter()) : null,
                failureStatuses,
                failureExceptions,
                breakerProps.getSlowCallDurationThreshold());
    }

    /**
     * The retry policy for {@code props}, built without touching any registry.
     *
     * <p>Public and separate because retry is the one policy that can be rebuilt under a running
     * client - see {@code ClientRuntime}. Rebuilding a breaker or a bulkhead the same way would
     * silently reset the window or the permits it is holding.
     */
    public RetryPolicy retryPolicy(String clientName, ClientProperties props) {
        ResilienceProperties resilience = props.getResilience();
        boolean on = Boolean.TRUE.equals(resilience.getEnabled());
        return new RetryPolicy(
                on ? resilience.getRetry() : disabledRetry(resilience),
                resolve(clientName, "retry.retry-on-exception",
                        resilience.getRetry().getRetryOnException()),
                clock);
    }

    /**
     * A retry configuration that cannot retry, used when {@code resilience.enabled: false}.
     *
     * <p>The alternative - a {@code null} retry policy - would put a null check on the hot path of
     * both pipelines for a case that is configuration, not behaviour.
     */
    private RetryProperties disabledRetry(ResilienceProperties source) {
        RetryProperties disabled = new RetryProperties();
        disabled.setEnabled(false);
        disabled.setMaxAttempts(1);
        disabled.setWaitDuration(source.getRetry().getWaitDuration());
        disabled.setExponentialBackoffMultiplier(source.getRetry().getExponentialBackoffMultiplier());
        disabled.setRandomizedWaitFactor(source.getRetry().getRandomizedWaitFactor());
        disabled.setMaxElapsedTime(source.getRetry().getMaxElapsedTime());
        disabled.setRetryOnStatus(List.of());
        disabled.setRetryOnException(List.of());
        disabled.setIdempotentMethodsOnly(true);
        disabled.setRespectRetryAfter(false);
        disabled.setMaxRetryAfter(source.getRetry().getMaxRetryAfter());
        return disabled;
    }

    private RateLimiter rateLimiter(String clientName, RateLimiterProperties props) {
        if (!Boolean.TRUE.equals(props.getEnabled())) {
            return null;
        }
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(props.getLimitForPeriod())
                .limitRefreshPeriod(props.getLimitRefreshPeriod())
                .timeoutDuration(props.getTimeoutDuration())
                .build();
        return registries.rateLimiters().rateLimiter(clientName, config);
    }

    private Bulkhead semaphoreBulkhead(String clientName, BulkheadProperties props) {
        if (!Boolean.TRUE.equals(props.getEnabled()) || props.getType() != BulkheadType.SEMAPHORE) {
            return null;
        }
        BulkheadConfig config = BulkheadConfig.custom()
                .maxConcurrentCalls(props.getMaxConcurrentCalls())
                .maxWaitDuration(props.getMaxWaitDuration())
                .build();
        return registries.bulkheads().bulkhead(clientName, config);
    }

    private ThreadPoolBulkhead threadPoolBulkhead(String clientName, BulkheadProperties props) {
        if (!Boolean.TRUE.equals(props.getEnabled()) || props.getType() != BulkheadType.THREAD_POOL) {
            return null;
        }
        ThreadPoolBulkheadConfig config = ThreadPoolBulkheadConfig.custom()
                .maxThreadPoolSize(props.getMaxConcurrentCalls())
                .coreThreadPoolSize(Math.min(props.getCoreThreadPoolSize(), props.getMaxConcurrentCalls()))
                .queueCapacity(Math.max(props.getQueueCapacity(), 1))
                .build();
        return registries.threadPoolBulkheads().bulkhead(clientName, config);
    }

    private CircuitBreaker circuitBreaker(String clientName, CircuitBreakerProperties props,
                                          Set<Integer> failureStatuses,
                                          List<Class<? extends Throwable>> failureExceptions) {
        if (!Boolean.TRUE.equals(props.getEnabled())) {
            return null;
        }
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(props.getFailureRateThreshold())
                .slowCallRateThreshold(props.getSlowCallRateThreshold())
                .slowCallDurationThreshold(props.getSlowCallDurationThreshold())
                .slidingWindowType(slidingWindowType(props.getSlidingWindowType()))
                .slidingWindowSize(props.getSlidingWindowSize())
                .minimumNumberOfCalls(props.getMinimumNumberOfCalls())
                .waitDurationInOpenState(props.getWaitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(props.getPermittedNumberOfCallsInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(
                        Boolean.TRUE.equals(props.getAutomaticTransitionFromOpenToHalfOpen()))
                // The pipeline reports a response as an AttemptOutcome, never as an exception, so
                // the breaker is told how to read one. Anything that is not an AttemptOutcome - or
                // whose status is not in the list - counts as a success, which is what keeps a 4xx
                // from opening a breaker on a healthy dependency.
                .recordResult(result -> result instanceof AttemptOutcome outcome
                        && failureStatuses.contains(outcome.statusCode()))
                .recordException(throwable -> isDependencyFailure(throwable, failureExceptions))
                .build();
        return registries.circuitBreakers().circuitBreaker(clientName, config);
    }

    private CircuitBreakerConfig.SlidingWindowType slidingWindowType(String value) {
        return "TIME_BASED".equalsIgnoreCase(value)
                ? CircuitBreakerConfig.SlidingWindowType.TIME_BASED
                : CircuitBreakerConfig.SlidingWindowType.COUNT_BASED;
    }

    private TimeLimiter timeLimiter(String clientName, ClientMode mode, TimeLimiterProperties props) {
        if (!Boolean.TRUE.equals(props.getEnabled()) || mode != ClientMode.ASYNC) {
            return null;
        }
        TimeLimiterConfig config = TimeLimiterConfig.custom()
                .timeoutDuration(props.getTimeout())
                .cancelRunningFuture(Boolean.TRUE.equals(props.getCancelRunningFuture()))
                .build();
        return registries.timeLimiters().timeLimiter(clientName, config);
    }

    /**
     * Resolves the class names in a {@code *-on-exception} list.
     *
     * <p>At startup, so a typo in a class name is a startup failure naming the key rather than a
     * policy that silently never matches - which is the worst kind of resilience bug, because it
     * looks configured and is not.
     */
    @SuppressWarnings("unchecked")
    private List<Class<? extends Throwable>> resolve(String clientName, String key, List<String> names) {
        List<Class<? extends Throwable>> resolved = new ArrayList<>();
        for (String name : names) {
            try {
                Class<?> type = Class.forName(name, false, classLoader);
                if (!Throwable.class.isAssignableFrom(type)) {
                    throw new IllegalStateException("Client '" + clientName + "': " + key + " lists '"
                            + name + "', which is not a Throwable.");
                }
                resolved.add((Class<? extends Throwable>) type);
            } catch (ClassNotFoundException ex) {
                throw new IllegalStateException("Client '" + clientName + "': " + key + " lists '"
                        + name + "', which is not on the classpath.", ex);
            }
        }
        return resolved;
    }

    /**
     * Whether an exception is the <em>dependency's</em> failure.
     *
     * <p>Resilience4j's default records every exception, which is wrong in both directions here. An
     * authentication failure is this service's own misconfiguration and must not open a breaker on a
     * partner that never saw the call; a deserialization error is a bug in this service's model.
     * Only a transport failure, or a type the configuration names, is charged to the dependency.
     */
    private static boolean isDependencyFailure(Throwable throwable,
                                               List<Class<? extends Throwable>> failureExceptions) {
        if (throwable instanceof RestClientAuthenticationException
                || throwable instanceof RestClientCallNotPermittedException) {
            return false;
        }
        if (TransportFailures.isTransportFailure(throwable)) {
            return true;
        }
        for (Class<? extends Throwable> type : failureExceptions) {
            if (type.isInstance(throwable)) {
                return true;
            }
        }
        return false;
    }
}
