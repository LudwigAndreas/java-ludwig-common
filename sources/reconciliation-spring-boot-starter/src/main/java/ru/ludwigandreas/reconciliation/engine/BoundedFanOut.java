package ru.ludwigandreas.reconciliation.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/**
 * Runs a list of calls on a shared executor, never more than {@code permits} of them at once, and
 * collects the results in input order.
 *
 * <h2>Why a semaphore over a shared pool, and not a pool per task</h2>
 *
 * <p>A thread pool per task means a service with eight integrations carries eight pools sized for
 * their individual peaks, all idle most of the time, and the total thread count is the sum of
 * concurrency limits nobody ever added up. One pool with a permit count per task caps each task
 * exactly as configured while letting the threads be shared, which is what {@code max-concurrency}
 * was always meant to express: how much of the partner's attention this task may take, not how much
 * memory it is entitled to.
 *
 * <h2>Why one call's failure does not abandon the rest</h2>
 *
 * <p>Each call's exception is turned into a result by the caller's own mapper, so a partner that
 * fails on one key still produces outcomes for the other 199 in the batch. Letting the first failure
 * propagate would mean one bad key costs a whole run's worth of successful fetches - which is the
 * failure mode this module exists to stop, expressed one level down.
 */
public final class BoundedFanOut {

    private static final Logger log = LoggerFactory.getLogger(BoundedFanOut.class);

    private BoundedFanOut() {
    }

    /**
     * Runs {@code work} over {@code inputs}.
     *
     * @param <T>      input type
     * @param <R>      result type
     * @param executor the shared executor
     * @param permits  how many calls may be in flight at once
     * @param inputs   the inputs
     * @param work     what to do with one input
     * @param onFailure turns an exception into a result for that input
     * @return the results, in input order
     */
    public static <T, R> List<R> run(Executor executor,
                                     int permits,
                                     List<T> inputs,
                                     Function<T, R> work,
                                     java.util.function.BiFunction<T, Throwable, R> onFailure) {
        if (inputs.isEmpty()) {
            return List.of();
        }
        Semaphore semaphore = new Semaphore(Math.max(1, permits));
        List<CompletableFuture<R>> futures = new ArrayList<>(inputs.size());
        for (T input : inputs) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                acquire(semaphore);
                try {
                    return work.apply(input);
                } finally {
                    semaphore.release();
                }
            }, executor).handle((result, error) -> error == null ? result : onFailure.apply(input, unwrap(error))));
        }
        List<R> results = new ArrayList<>(futures.size());
        for (int i = 0; i < futures.size(); i++) {
            results.add(await(futures.get(i), inputs.get(i), onFailure));
        }
        return results;
    }

    private static <T, R> R await(CompletableFuture<R> future,
                                  T input,
                                  java.util.function.BiFunction<T, Throwable, R> onFailure) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return onFailure.apply(input, e);
        } catch (ExecutionException e) {
            // The handle() above already converts failures, so reaching here means the failure mapper
            // itself threw. Report it against the same input rather than losing the whole batch.
            log.warn("Failure mapper threw while handling a fetch failure", e);
            return onFailure.apply(input, unwrap(e));
        }
    }

    private static void acquire(Semaphore semaphore) {
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a concurrency permit", e);
        }
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof CompletionException || error instanceof ExecutionException) {
            return error.getCause() == null ? error : error.getCause();
        }
        return error;
    }
}
