package ru.ludwigandreas.cache.core;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * The small pool that runs early refreshes.
 *
 * <h2>Why a dedicated type rather than a bare {@code Executor} bean</h2>
 *
 * <p>An {@code Executor} bean on a Spring context is a candidate for Spring's own async and scheduling
 * infrastructure, so publishing one from a starter can silently become the application's task executor.
 * This is a type nothing else asks for.
 *
 * <h2>Bounded, and it discards</h2>
 *
 * <p>The queue is bounded and the saturation policy is discard, which is the opposite of what a queue for
 * important work would do, and it is right here. An early refresh is work nobody is waiting for: the entry
 * it would have refreshed is still fresh, and the ordinary expiry path will load it. The alternatives are
 * both worse - an unbounded queue turns a slow store into heap pressure, and
 * {@code CallerRunsPolicy} runs the refresh on the request thread, which is exactly the latency this
 * feature exists to remove.
 *
 * <p>The threads are daemons. A refresh in flight must never be the reason a pod takes longer to shut down.
 */
@Slf4j
public class CacheRefreshExecutor implements Executor {

    /** How long an idle refresh thread beyond the core size is kept. */
    private static final long KEEP_ALIVE_SECONDS = 30L;

    private final ThreadPoolExecutor delegate;

    /**
     * @param poolSize      how many threads run refreshes
     * @param queueCapacity how many refreshes may wait before further ones are dropped
     */
    public CacheRefreshExecutor(int poolSize, int queueCapacity) {
        AtomicInteger counter = new AtomicInteger();
        this.delegate = new ThreadPoolExecutor(poolSize, poolSize, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable,
                            "ludwig-cache-refresh-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.DiscardPolicy());
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(command);
    }

    /** Stops accepting refreshes. Called when the context closes; in-flight refreshes are not awaited. */
    public void shutdown() {
        delegate.shutdownNow();
    }
}
