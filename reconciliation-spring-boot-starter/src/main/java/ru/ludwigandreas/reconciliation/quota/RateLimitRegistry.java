package ru.ludwigandreas.reconciliation.quota;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import ru.ludwigandreas.reconciliation.config.ReconciliationProperties;
import ru.ludwigandreas.reconciliation.config.TaskSettings;

import java.util.Map;
import java.util.Optional;

/**
 * The named, partner-scoped request-rate budgets.
 *
 * <h2>Why a rate limit is per partner and not per task</h2>
 *
 * <p>For the same reason a quota is: "100 requests a minute" is something the partner said about
 * itself, and two tasks that each respect a limit of 100 against the same partner send 200. Naming
 * the limit and having tasks reference it is what makes the budget shared rather than multiplied.
 *
 * <h2>Why resilience4j is enough here, unlike for quotas</h2>
 *
 * <p>Because a rate limiter paces calls this process is about to make, and each process has its own
 * connection to the partner anyway. The configured rate is therefore per instance, and a deployment
 * with three replicas must divide the partner's published rate by three - which is stated in the
 * README rather than papered over, because the alternative is a distributed token bucket whose
 * coordination costs a database round trip on every single call.
 *
 * <p>A quota cannot be handled this way, and the difference is worth being precise about: a quota
 * counts long-lived remote work, where the coordination cost is negligible against a job that runs
 * for minutes, and where being wrong means running more jobs at the partner than they allow.
 */
public class RateLimitRegistry {

    private final RateLimiterRegistry registry;
    private final Map<String, ReconciliationProperties.RateLimit> configured;

    /**
     * Creates the registry.
     *
     * @param rateLimits the configured limits, by name
     */
    public RateLimitRegistry(Map<String, ReconciliationProperties.RateLimit> rateLimits) {
        this.configured = Map.copyOf(rateLimits);
        this.registry = RateLimiterRegistry.ofDefaults();
    }

    /**
     * Takes a permit for a task's next call, if the task is gated by a rate limit.
     *
     * @param settings the task's settings
     * @throws RateLimitExceededException if no permit became available within the limit's timeout
     */
    public void acquire(TaskSettings settings) {
        settings.rateLimitName().ifPresent(name -> acquire(name, settings.name()));
    }

    /**
     * Takes a permit from a named limit.
     *
     * @param limitName the limit
     * @param taskName  the task taking it, for the error message
     * @throws RateLimitExceededException if no permit became available within the limit's timeout
     */
    public void acquire(String limitName, String taskName) {
        ReconciliationProperties.RateLimit limit = configured.get(limitName);
        if (limit == null) {
            // Unreachable in a validated context: the startup validator refuses a task that
            // references a limit that is not configured. Treated as a no-op rather than a failure so
            // that a test wiring the registry directly is not obliged to configure everything.
            return;
        }
        if (!limiter(limitName, limit).acquirePermission()) {
            throw new RateLimitExceededException("Task '" + taskName + "' did not get a permit from rate "
                    + "limit '" + limitName + "' within " + limit.getTimeout()
                    + "; the call was not made and the key backs off like any other transient failure");
        }
    }

    /** The configured names, for the startup validator and the actuator endpoint. */
    public java.util.Set<String> names() {
        return configured.keySet();
    }

    /**
     * The configuration of a named limit.
     *
     * @param limitName the limit
     * @return its configuration, if it exists
     */
    public Optional<ReconciliationProperties.RateLimit> find(String limitName) {
        return Optional.ofNullable(configured.get(limitName));
    }

    private RateLimiter limiter(String name, ReconciliationProperties.RateLimit limit) {
        return registry.rateLimiter(name, () -> RateLimiterConfig.custom()
                .limitForPeriod(limit.getPermits())
                .limitRefreshPeriod(limit.getPer())
                .timeoutDuration(limit.getTimeout())
                .build());
    }
}
