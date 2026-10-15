package ru.ludwigandreas.grpc.server.server;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The executor gRPC runs handlers on: a fixed pool, a bounded queue, and a way to say no.
 *
 * <h2>Why not just a bounded {@code ThreadPoolExecutor}</h2>
 *
 * <p>gRPC's default is an unbounded cached pool, which under load is an unbounded number of threads
 * each holding a database connection it cannot get. The obvious fix - a fixed pool with a bounded
 * queue - has a different problem: when the queue is full the pool <em>throws</em>, on gRPC's
 * transport thread, and what the caller sees is a reset stream or an {@code INTERNAL}, not a
 * {@code RESOURCE_EXHAUSTED} it could back off from. And the refusal cannot be made from inside the
 * pool, because the pool is the thing that has no thread to spare.
 *
 * <p>So a task the pool rejects is handed to one separate <b>overflow</b> thread, marked as such.
 * The admission stage of a new call asks {@link #onOverflowThread()} and, if so, refuses the call
 * with {@code RESOURCE_EXHAUSTED} before authenticating it or running a handler. Refusing is cheap,
 * so one thread is enough to refuse as fast as callers can ask.
 *
 * <p>The stated cost: an event of a call that was <em>already admitted</em> - its half-close
 * arriving after the queue filled - can also overflow, and then that handler runs on the overflow
 * thread. It runs correctly, one at a time, slower. That is the right trade: the alternative is to
 * drop an event of a call the server has accepted.
 */
public final class BoundedCallExecutor implements Executor, AutoCloseable {

    private static final ThreadLocal<Boolean> OVERFLOW = new ThreadLocal<>();

    private final ThreadPoolExecutor workers;
    private final ExecutorService overflow;

    /**
     * Creates the executor.
     *
     * @param threads       handler threads
     * @param queueCapacity tasks that may wait for one; {@code 0} hands off directly
     */
    public BoundedCallExecutor(int threads, int queueCapacity) {
        BlockingQueue<Runnable> queue = queueCapacity <= 0
                ? new SynchronousQueue<>()
                : new ArrayBlockingQueue<>(queueCapacity);
        this.workers = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS, queue,
                named("ludwig-grpc-"));
        this.overflow = Executors.newSingleThreadExecutor(named("ludwig-grpc-overflow-"));
    }

    /**
     * Whether the current thread is the overflow thread, i.e. the pool had no room for this task.
     *
     * @return {@code true} on the overflow thread
     */
    public static boolean onOverflowThread() {
        return Boolean.TRUE.equals(OVERFLOW.get());
    }

    @Override
    public void execute(Runnable task) {
        try {
            workers.execute(task);
        } catch (RejectedExecutionException saturated) {
            overflow.execute(() -> {
                OVERFLOW.set(Boolean.TRUE);
                try {
                    task.run();
                } finally {
                    OVERFLOW.remove();
                }
            });
        }
    }

    /**
     * Handler threads currently running a task; for the saturation gauge.
     *
     * @return the active thread count
     */
    public int active() {
        return workers.getActiveCount();
    }

    /**
     * Tasks waiting for a handler thread.
     *
     * @return the queue depth
     */
    public int queued() {
        return workers.getQueue().size();
    }

    @Override
    public void close() {
        workers.shutdown();
        overflow.shutdown();
    }

    private static ThreadFactory named(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
