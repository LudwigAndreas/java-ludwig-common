package ru.ludwigandreas.grpc.client.channel;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.grpc.Channel;
import io.grpc.ManagedChannel;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import ru.ludwigandreas.grpc.client.properties.ClientSettings;

/**
 * One client: its channel, and the part of its settings a refresh may change.
 *
 * <p>Everything baked into the channel - target, credentials, TLS, service config, breaker window -
 * needs a restart. The deadline, the logging level, the rate limiter's limit and the audit sampling
 * are re-read from the bound properties, so whatever rebinds them (hot reload, an actuator refresh)
 * takes effect without one. The re-read is throttled to once a second and is one volatile read on
 * the fast path; a call in flight keeps the settings it started with, because each stage reads them
 * once, when the call starts.
 */
public final class ClientRuntime {

    private static final long REFRESH_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final String name;
    private final ManagedChannel managed;
    private final Supplier<ClientSettings> resolver;
    private final AtomicLong lastRefresh = new AtomicLong(System.nanoTime());
    private volatile Channel intercepted;
    private volatile ClientSettings settings;
    private volatile RateLimiter rateLimiter;

    /**
     * Creates the runtime.
     *
     * @param name     the client
     * @param managed  the channel
     * @param resolver re-resolves the client's settings from the bound properties
     */
    public ClientRuntime(String name, ManagedChannel managed, Supplier<ClientSettings> resolver) {
        this.name = name;
        this.managed = managed;
        this.resolver = resolver;
        this.settings = resolver.get();
    }

    /**
     * Completes the runtime with the pipeline built around its channel.
     *
     * @param pipeline    the intercepted channel
     * @param rateLimiter the client's rate limiter, or {@code null}
     */
    public void complete(Channel pipeline, RateLimiter rateLimiter) {
        this.intercepted = pipeline;
        this.rateLimiter = rateLimiter;
    }

    /**
     * The client name.
     *
     * @return the name
     */
    public String name() {
        return name;
    }

    /**
     * The raw channel, for shutdown.
     *
     * @return the managed channel
     */
    public ManagedChannel managed() {
        return managed;
    }

    /**
     * The channel every consumption model hands out.
     *
     * @return the intercepted channel
     */
    public Channel channel() {
        return intercepted;
    }

    /**
     * The current settings, re-read from the bound properties at most once a second.
     *
     * @return the settings
     */
    public ClientSettings settings() {
        long last = lastRefresh.get();
        long now = System.nanoTime();
        if (now - last >= REFRESH_NANOS && lastRefresh.compareAndSet(last, now)) {
            ClientSettings fresh = resolver.get();
            settings = fresh;
            RateLimiter limiter = rateLimiter;
            ClientSettings.RateLimiter declared = fresh.getResilience().getRateLimiter();
            if (limiter != null && declared != null && declared.getLimitForPeriod() != null
                    && declared.getLimitForPeriod() != limiter.getRateLimiterConfig().getLimitForPeriod()) {
                limiter.changeLimitForPeriod(declared.getLimitForPeriod());
            }
        }
        return settings;
    }
}
