package ru.ludwigandreas.export.engine;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads the source, enriches, and hands finished windows to the writer across a bounded queue.
 *
 * <h2>This is where {@code handoff-queue-depth} finally means something</h2>
 *
 * <p>Until enrichment existed there was nothing to decouple: the source walk was cheap and the
 * writer ran on the same thread, so a queue would have cost a thread per run and bought a hand-off.
 * With enrichment in the pipeline the two halves wait on completely different things - enrichment on
 * partner latency, writing on CPU and disk - and overlapping them is worth a thread.
 *
 * <p>The queue's depth <em>is</em> the backpressure, and it is the reason this is a queue rather
 * than a prefetch of everything: a producer that ran ahead without limit would hold the whole
 * report in memory, which is precisely what windowing exists to prevent. Four windows is enough to
 * keep the writer busy through a partner's latency spike and small enough to stay inside the
 * memory bound the window size defines.
 *
 * <h2>Who owns what</h2>
 *
 * <p>The producer thread owns the source stream for its whole life and closes it on every exit path,
 * including being cancelled mid-{@code put}. The consumer owns the writers. Neither touches the
 * other's resources, which is what makes the failure paths tractable: a writer that throws closes
 * the pump, the pump interrupts the producer, the producer's {@code put} throws
 * {@code InterruptedException}, and its try-with-resources closes the stream on the way out.
 *
 * <h2>Degrading to synchronous</h2>
 *
 * <p>With no executor, or with one that refuses the task, the pump reads and enriches on the
 * consumer's thread. That is a real fallback rather than a failure: the pipeline is correct either
 * way and only the overlap is lost, which is a far better outcome than refusing to run a report
 * because a pool was saturated.
 *
 * @param <T> the type a finished window has
 */
@Slf4j
final class WindowPump<T> implements AutoCloseable {

    /** What the producer puts on the queue when there is nothing more to read. */
    private static final Object END = new Object();

    private final BlockingQueue<Object> queue;
    private final Supplier<DirectRun<T>> direct;
    private Future<?> producer;
    private DirectRun<T> opened;

    private WindowPump(BlockingQueue<Object> queue, Supplier<DirectRun<T>> direct) {
        this.queue = queue;
        this.direct = direct;
    }

    /**
     * Starts a pump.
     *
     * @param executor where the producer runs, or null to read on the consumer's thread
     * @param depth    how many finished windows may wait for the writer
     * @param source   opens the row stream; closed by whichever thread ends up walking it
     * @param windows  turns the stream into finished windows
     * @param <R>      the row type
     * @param <T>      the finished window type
     * @return a pump the caller must close
     */
    static <R, T> WindowPump<T> start(ExecutorService executor, int depth,
                                      Supplier<Stream<R>> source,
                                      Function<Stream<R>, Iterator<T>> windows) {
        if (executor == null) {
            return synchronous(source, windows);
        }
        WindowPump<T> pump = new WindowPump<>(new ArrayBlockingQueue<>(Math.max(1, depth)), null);
        try {
            pump.producer = executor.submit(() -> pump.produce(source, windows));
            return pump;
        } catch (RejectedExecutionException e) {
            log.debug("No capacity to prefetch report windows; reading on the calling thread: {}",
                    e.toString());
            return synchronous(source, windows);
        }
    }

    /**
     * The fallback that reads on the consumer's thread.
     *
     * <p>The stream is kept alongside its iterator rather than only the iterator, because the pump
     * is what closes it: an iterator is not {@link AutoCloseable}, so a pump holding only the
     * iterator would have no way to release the database cursor behind it - which is a connection
     * leaked per run, and the one failure this fallback could plausibly introduce.
     */
    private static <R, T> WindowPump<T> synchronous(Supplier<Stream<R>> source,
                                                    Function<Stream<R>, Iterator<T>> windows) {
        return new WindowPump<>(null, () -> {
            Stream<R> rows = source.get();
            return new DirectRun<>(windows.apply(rows), rows);
        });
    }

    /**
     * The next finished window, or empty when there are no more.
     *
     * @throws RuntimeException the producer's failure, rethrown on the consumer's thread so that the
     *                          engine's one failure path handles a source or enrichment failure the
     *                          same way it handles a writer failure
     */
    Optional<T> next() {
        if (queue == null) {
            return nextDirect();
        }
        Object taken = take();
        if (taken == END) {
            return Optional.empty();
        }
        if (taken instanceof Failure failure) {
            throw failure.rethrow();
        }
        @SuppressWarnings("unchecked")
        T window = (T) taken;
        return Optional.of(window);
    }

    @Override
    public void close() {
        if (producer != null) {
            // Interrupts a producer blocked on put(), so it can close the source stream and exit
            // rather than waiting forever for a consumer that has already failed.
            producer.cancel(true);
            queue.clear();
        }
        if (opened != null) {
            closeQuietly(opened.closeable());
            opened = null;
        }
    }

    private Optional<T> nextDirect() {
        if (opened == null) {
            opened = direct.get();
        }
        return opened.iterator().hasNext() ? Optional.of(opened.iterator().next()) : Optional.empty();
    }

    private <R> void produce(Supplier<Stream<R>> source, Function<Stream<R>, Iterator<T>> windows) {
        try (Stream<R> rows = source.get()) {
            Iterator<T> iterator = windows.apply(rows);
            while (iterator.hasNext()) {
                queue.put(iterator.next());
            }
            queue.put(END);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // The consumer gave up. The stream is closed by the try-with-resources on the way out,
            // and there is nobody left to report to.
        } catch (RuntimeException e) {
            offerFailure(e);
        }
    }

    private void offerFailure(RuntimeException e) {
        try {
            // A clear() by close() may have freed room, but the consumer may also be gone; offering
            // rather than putting means a failure never leaves this thread blocked forever.
            if (!queue.offer(new Failure(e))) {
                queue.clear();
                queue.offer(new Failure(e));
            }
        } catch (RuntimeException ignored) {
            log.debug("Could not hand a report failure to the consumer", e);
        }
    }

    private Object take() {
        try {
            return queue.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        }
    }

    private void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            log.warn("Could not close the report window iterator: {}", e.toString());
        }
    }

    /** Collects an iterator into windows of at most {@code size} elements. */
    static <R> List<R> fill(Iterator<R> rows, int size) {
        List<R> window = new ArrayList<>(size);
        while (window.size() < size && rows.hasNext()) {
            window.add(rows.next());
        }
        return window;
    }

    /** An open source stream and the windows being read from it, on the synchronous path. */
    private record DirectRun<T>(Iterator<T> iterator, AutoCloseable closeable) {
    }

    /** A producer failure on its way to the consumer's thread. */
    private record Failure(RuntimeException cause) {

        RuntimeException rethrow() {
            return cause;
        }
    }
}
