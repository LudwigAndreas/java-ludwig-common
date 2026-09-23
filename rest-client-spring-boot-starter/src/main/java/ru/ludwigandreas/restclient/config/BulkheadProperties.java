package ru.ludwigandreas.restclient.config;

import jakarta.validation.constraints.Positive;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;

/** Concurrency cap for one named client. */
@Getter
@Setter
public class BulkheadProperties {

    /**
     * Built-in default: false.
     *
     * <p>Off by default because a concurrency cap is the one resilience setting whose right value
     * cannot be guessed: too low and it throttles healthy traffic, too high and it does nothing. It
     * is also the setting that matters most when a dependency goes slow, so every client that
     * carries a request thread should have one.
     */
    private Boolean enabled;

    /** {@code SEMAPHORE} or {@code THREAD_POOL}. Built-in default: SEMAPHORE. */
    private BulkheadType type;

    /** Calls allowed to be in flight at once. Built-in default: 25. */
    @Positive
    private Integer maxConcurrentCalls;

    /**
     * How long a caller waits for a permit before being rejected. Built-in default: 0.
     *
     * <p>Zero - fail immediately - is deliberate. A queue in front of a saturated dependency
     * converts a fast, visible rejection into latency for everybody, and the caller usually has a
     * deadline of its own; a rejection at least leaves it time to do something else.
     */
    private Duration maxWaitDuration;

    /** THREAD_POOL only: core threads. Built-in default: max-concurrent-calls. */
    @Positive
    private Integer coreThreadPoolSize;

    /** THREAD_POOL only: queue depth in front of the pool. Built-in default: 0. */
    private Integer queueCapacity;
}
