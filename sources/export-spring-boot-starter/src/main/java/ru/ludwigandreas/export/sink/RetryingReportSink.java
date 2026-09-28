package ru.ludwigandreas.export.sink;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.export.api.ReportFormat;
import ru.ludwigandreas.export.api.ReportSink;
import ru.ludwigandreas.export.api.StoredOutput;
import ru.ludwigandreas.job.core.backoff.BackoffCalculator;
import ru.ludwigandreas.job.core.backoff.BackoffPolicy;

/**
 * Retries a store, and only a store, before the run gives up on it.
 *
 * <h2>Why a decorator rather than a loop in the engine</h2>
 *
 * <p>Because the engine's retry is a different thing at a different scale. A failed <em>run</em> is
 * retried by the poller, minutes later, from scratch - which for a million-row report means walking
 * the source and calling the partners again. A failed <em>store</em> is a few hundred milliseconds of
 * network trouble in front of a file that is finished and sitting on disk. Making the run's retry
 * cover it would mean paying for the whole report again because an upload blipped.
 *
 * <p>Wrapping the sink rather than special-casing it in the engine also means the seam stays honest:
 * a service that supplies its own sink gets the same retry, and a sink that would rather do its own
 * is documented as not needing to - {@code ReportSink} says implementations must not retry
 * internally, precisely so the two budgets are not multiplied together.
 *
 * <h2>Only store is retried</h2>
 *
 * <p>{@code open} is on the download path, where a caller is waiting and a failure is better reported
 * than slept on; {@code delete} is on the purge path, which runs again in an hour and is idempotent.
 * Neither benefits from a retry here, and both would be worse for holding a request thread.
 */
@Slf4j
public class RetryingReportSink implements ReportSink {

    private final ReportSink delegate;
    private final int attempts;
    private final BackoffCalculator backoff;

    /**
     * Wraps a sink.
     *
     * @param delegate where the file actually goes
     * @param attempts total attempts, including the first; one means no retry at all
     * @param backoff  the curve between attempts, from {@code job-core} so that a store's delays look
     *                 like every other delay in the platform - jittered, multiplied, capped
     */
    public RetryingReportSink(ReportSink delegate, int attempts, BackoffPolicy backoff) {
        if (delegate == null) {
            throw new IllegalArgumentException("A RetryingReportSink needs a delegate");
        }
        this.delegate = delegate;
        this.attempts = Math.max(1, attempts);
        this.backoff = new BackoffCalculator(backoff);
    }

    @Override
    public StoredOutput store(UUID runId, String fileName, ReportFormat format, Path file)
            throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return delegate.store(runId, fileName, format, file);
            } catch (IOException e) {
                last = e;
                if (attempt == attempts) {
                    break;
                }
                Duration delay = backoff.nextDelay(attempt);
                log.warn("Storing the output of report run {} failed on attempt {} of {}; retrying in"
                        + " {}: {}", runId, attempt, attempts, delay, e.toString());
                if (!pause(delay)) {
                    break;
                }
            }
        }
        throw last;
    }

    @Override
    public InputStream open(String uri) throws IOException {
        return delegate.open(uri);
    }

    @Override
    public void delete(String uri) throws IOException {
        delegate.delete(uri);
    }

    /**
     * Waits between attempts.
     *
     * <p>The one deliberate sleep in this module, and it is on the run's own thread rather than on a
     * scheduler: the run is holding a finished file it must either store or delete, so parking the
     * work elsewhere would mean persisting a hand-off for something that resolves in under a second.
     * An interrupt means the run is being drained, so it stops trying rather than finishing the curve.
     *
     * @return false when the thread was interrupted and the caller should give up
     */
    private boolean pause(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
