package ru.ludwigandreas.export.enrich;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/**
 * Runs a stage's calls on a shared executor, never more than {@code permits} of them at once.
 *
 * <h2>A semaphore over a shared pool, not a pool per stage</h2>
 *
 * <p>A pool per stage means a service with eight report definitions carries a pool per stage across
 * all of them, each sized for its own peak, all idle most of the time, and a total thread count
 * nobody ever added up. One pool with a permit count per stage caps each stage exactly as configured
 * while letting the threads be shared - which is what {@code concurrency} was always meant to
 * express: how much of the partner's attention this stage may take, not how much memory it is
 * entitled to.
 *
 * <h2>Every call finishes before a failure is reported</h2>
 *
 * <p>A stage is all-or-nothing - one chunk failing means the stage could not answer this window, and
 * {@code FailurePolicy} decides what that means - so the first failure is rethrown. It is rethrown
 * <em>after</em> every other call has completed rather than immediately, because returning while
 * calls are still in flight would leave a partner being called on behalf of a run that has already
 * given up, and would leave those threads occupied against the next window's budget.
 *
 * <h2>On the duplication</h2>
 *
 * <p>{@code reconciliation-spring-boot-starter} has a class of the same name doing the same thing
 * for its own fetch shapes, and this is a deliberate copy rather than a dependency: depending on
 * that starter for one utility would drag its entities, its Liquibase changelog and its schedulers
 * into every service that exports a spreadsheet. A shared home for both would have to be a new
 * dependency-free library module; introducing one changes another module's published surface and
 * belongs in its own commit.
 */
final class BoundedFanOut {

    private BoundedFanOut() {
    }

    /**
     * Runs {@code work} over {@code inputs} and returns the results in input order.
     *
     * @param executor the shared executor, or null to run on the calling thread
     * @param permits  how many calls may be in flight at once
     * @param inputs   the inputs
     * @param work     what to do with one input
     * @param <T>      input type
     * @param <R>      result type
     * @return the results, in input order
     */
    static <T, R> List<R> run(Executor executor, int permits, List<T> inputs, Function<T, R> work) {
        if (inputs.isEmpty()) {
            return List.of();
        }
        if (inputs.size() == 1 || executor == null) {
            // One chunk, or no executor at all: running it here avoids a hand-off that buys nothing
            // and keeps the engine working in a context that never configured a pool.
            return inputs.stream().map(work).toList();
        }
        Semaphore semaphore = new Semaphore(Math.max(1, permits));
        List<CompletableFuture<R>> futures = new ArrayList<>(inputs.size());
        for (T input : inputs) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> withPermit(semaphore, input, work), executor));
        }
        return collect(futures);
    }

    private static <T, R> R withPermit(Semaphore semaphore, T input, Function<T, R> work) {
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        }
        try {
            return work.apply(input);
        } finally {
            semaphore.release();
        }
    }

    private static <R> List<R> collect(List<CompletableFuture<R>> futures) {
        List<R> results = new ArrayList<>(futures.size());
        RuntimeException failure = null;
        for (CompletableFuture<R> future : futures) {
            try {
                results.add(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = failure == null ? new CompletionException(e) : failure;
            } catch (ExecutionException e) {
                RuntimeException unwrapped = unwrap(e.getCause());
                failure = failure == null ? unwrapped : failure;
            }
        }
        if (failure != null) {
            throw failure;
        }
        return results;
    }

    private static RuntimeException unwrap(Throwable cause) {
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        return new CompletionException(cause);
    }
}
