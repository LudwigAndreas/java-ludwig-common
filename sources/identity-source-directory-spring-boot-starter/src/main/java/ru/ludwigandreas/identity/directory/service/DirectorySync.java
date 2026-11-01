package ru.ludwigandreas.identity.directory.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ludwigandreas.identity.directory.DirectoryPage;
import ru.ludwigandreas.identity.directory.DirectoryQuery;
import ru.ludwigandreas.identity.directory.IdentityDirectory;
import ru.ludwigandreas.identity.directory.repository.DirectoryState;
import ru.ludwigandreas.identity.directory.repository.DirectoryStateStore;
import ru.ludwigandreas.identity.feed.IdentityChange;
import ru.ludwigandreas.identity.feed.IdentityProjection;
import ru.ludwigandreas.identity.feed.IdentitySweep.SweepResult;
import ru.ludwigandreas.identity.feed.IdentitySweep;

/**
 * Reads one directory into the projection: incrementally from the stored watermark, or completely as a sweep.
 *
 * <p>An incremental sync commits each page's changes and the watermark it ends with in one transaction, so a sync
 * stopped mid-way resumes after the last committed page; a change at a page boundary is applied twice and skipped as
 * stale the second time. A sweep stages every listed subject and, when the listing ends, disables the source's users
 * it did not list - unless the projection's threshold aborts it. A sweep that fails before the listing ends is
 * abandoned and disables nobody. A failure is recorded as the source's outcome - its type, never its message - and
 * rethrown to the job, whose next run is the retry.
 */
public class DirectorySync {

    private static final Logger log = LoggerFactory.getLogger(DirectorySync.class);

    private final IdentityDirectory directory;
    private final IdentityProjection projection;
    private final DirectoryStateStore state;
    private final TransactionTemplate transactions;
    private final DirectoryMetrics metrics;
    private final int pageSize;

    /**
     * Creates the sync for one directory.
     *
     * @param directory    the directory
     * @param projection   the projection it feeds
     * @param state        where progress is kept
     * @param transactions a required-propagation transaction template
     * @param metrics      times each run
     * @param pageSize     the page size asked for
     */
    public DirectorySync(IdentityDirectory directory, IdentityProjection projection, DirectoryStateStore state,
                         TransactionTemplate transactions, DirectoryMetrics metrics, int pageSize) {
        this.directory = directory;
        this.projection = projection;
        this.state = state;
        this.transactions = transactions;
        this.metrics = metrics;
        this.pageSize = pageSize;
    }

    /**
     * The source this sync reads.
     *
     * @return the directory's source
     */
    public String source() {
        return directory.source();
    }

    /** Applies the changes since the stored watermark; sweeps instead when there is none or no change filter. */
    public void sync() {
        String source = directory.source();
        Instant watermark = state.read(source).map(DirectoryState::watermark).orElse(null);
        if (watermark == null || !directory.filtersByChange()) {
            sweep();
            return;
        }
        long started = System.nanoTime();
        int pages = 0;
        int changes = 0;
        try {
            String cursor = null;
            do {
                DirectoryPage page = directory.fetch(new DirectoryQuery(cursor, watermark, pageSize));
                transactions.executeWithoutResult(status -> {
                    projection.applyAll(page.changes());
                    if (page.watermark() != null) {
                        state.advanceWatermark(source, page.watermark());
                    }
                });
                pages++;
                changes += page.changes().size();
                cursor = page.nextCursor();
            } while (cursor != null);
            state.recordSync(source, "succeeded", null, pages, changes);
            metrics.run(source, "sync", "succeeded", since(started));
        } catch (RuntimeException e) {
            state.recordSync(source, "failed", e.getClass().getName(), pages, changes);
            metrics.run(source, "sync", "failed", since(started));
            throw e;
        }
    }

    /** Lists the source completely and disables its users the listing no longer contains. */
    public void sweep() {
        String source = directory.source();
        long started = System.nanoTime();
        IdentitySweep sweep = projection.beginSweep(source);
        int pages = 0;
        Instant highest = null;
        try {
            String cursor = null;
            do {
                DirectoryPage page = directory.fetch(new DirectoryQuery(cursor, null, pageSize));
                List<String> subjects = page.changes().stream().map(IdentityChange::subject).toList();
                transactions.executeWithoutResult(status -> {
                    projection.applyAll(page.changes());
                    sweep.listed(subjects);
                });
                pages++;
                if (page.watermark() != null && (highest == null || page.watermark().isAfter(highest))) {
                    highest = page.watermark();
                }
                cursor = page.nextCursor();
            } while (cursor != null);
        } catch (RuntimeException e) {
            sweep.abandon();
            state.recordSweep(source, "failed", e.getClass().getName(), pages, 0);
            metrics.run(source, "sweep", "failed", since(started));
            throw e;
        }
        SweepResult result = sweep.complete();
        // An aborted listing may have been truncated, so its highest watermark may lie past changes it never saw.
        if (!result.aborted() && highest != null) {
            state.advanceWatermark(source, highest);
        }
        String outcome = result.aborted() ? "aborted" : "succeeded";
        state.recordSweep(source, outcome, null, pages, result.disabled());
        metrics.run(source, "sweep", outcome, since(started));
        log.info("Swept identity source {}: {} pages, {} users disabled{}", source, pages, result.disabled(),
                result.aborted() ? " (aborted by the disable threshold)" : "");
    }

    private static Duration since(long started) {
        return Duration.ofNanos(System.nanoTime() - started);
    }
}
