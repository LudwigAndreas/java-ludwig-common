package ru.ludwigandreas.idempotency.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * {@link IdempotencyMetrics} over Micrometer.
 *
 * <p>Counters are resolved once per tag combination and cached, rather than looked up on every claim.
 * The set of combinations is bounded by the number of scopes times the number of outcomes, both of which
 * are fixed at deploy time - which is what makes caching them safe here and would make it a leak if the
 * key were ever a tag.
 */
public class MicrometerIdempotencyMetrics implements IdempotencyMetrics {

    /** Metric names, namespaced like every other meter this platform publishes. */
    private static final String CLAIMS = "ludwig.idempotency.claims";
    private static final String REPLAYS = "ludwig.idempotency.replays";
    private static final String MISMATCHES = "ludwig.idempotency.fingerprint.mismatches";
    private static final String FAILURES = "ludwig.idempotency.claims.failed";
    private static final String PURGED = "ludwig.idempotency.purged";

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();

    /**
     * Creates the binding.
     *
     * @param registry the registry
     */
    public MicrometerIdempotencyMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void claim(String scope, String outcome) {
        counter(CLAIMS, scope, outcome).increment();
    }

    @Override
    public void replayed(String scope) {
        counter(REPLAYS, scope, null).increment();
    }

    @Override
    public void fingerprintMismatch(String scope) {
        counter(MISMATCHES, scope, null).increment();
    }

    @Override
    public void failed(String scope) {
        counter(FAILURES, scope, null).increment();
    }

    @Override
    public void purged(long purged) {
        counter(PURGED, null, null).increment(purged);
    }

    private Counter counter(String name, String scope, String outcome) {
        String cacheKey = name + '|' + scope + '|' + outcome;
        return counters.computeIfAbsent(cacheKey, ignored -> {
            Counter.Builder builder = Counter.builder(name);
            if (scope != null) {
                builder.tag("scope", scope);
            }
            if (outcome != null) {
                builder.tag("outcome", outcome);
            }
            return builder.register(registry);
        });
    }
}
