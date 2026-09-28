package ru.ludwigandreas.restclient.transport;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.function.ToDoubleFunction;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.pool.PoolStats;

/**
 * Publishes pool occupancy for the {@code apache} transport.
 *
 * <p>Four gauges, tagged {@code client}: {@code leased} (in use), {@code pending} (callers waiting
 * for a connection), {@code available} (idle and reusable) and {@code max}. Pending is the one that
 * matters: it is zero in every healthy state and rises the moment a dependency slows down, several
 * seconds before latency does - which makes it the earliest honest warning a dashboard can carry.
 * Leased alone cannot say that, because a pool can be fully leased and perfectly healthy.
 *
 * <p>Nothing is published for the JDK engine. It exposes no pool statistics at all, and a gauge
 * reading zero is indistinguishable, on a graph, from a pool that is never busy.
 */
public final class ConnectionPoolGauges {

    private static final String PREFIX = "ludwig.restclient.pool.";

    private ConnectionPoolGauges() {
    }

    /**
     * Binds the gauges for {@code poolHandle} if it is a pool this class understands.
     *
     * <p>Takes {@code Object} and narrows it here so that the calling code - which runs whether or
     * not Apache HttpClient is on the classpath - never names an Apache type in a signature.
     */
    public static void bind(MeterRegistry registry, String clientName, Object poolHandle) {
        if (!(poolHandle instanceof PoolingHttpClientConnectionManager pool)) {
            return;
        }
        Tags tags = Tags.of("client", clientName);
        gauge(registry, "leased", tags, pool, stats -> stats.getLeased());
        gauge(registry, "pending", tags, pool, stats -> stats.getPending());
        gauge(registry, "available", tags, pool, stats -> stats.getAvailable());
        gauge(registry, "max", tags, pool, stats -> stats.getMax());
    }

    private static void gauge(MeterRegistry registry, String name, Tags tags,
                              PoolingHttpClientConnectionManager pool,
                              ToDoubleFunction<PoolStats> reader) {
        Gauge.builder(PREFIX + name, pool, p -> reader.applyAsDouble(p.getTotalStats()))
                .tags(tags)
                .description("Connections in the '" + name + "' state for this named client's pool")
                .baseUnit("connections")
                .register(registry);
    }
}
