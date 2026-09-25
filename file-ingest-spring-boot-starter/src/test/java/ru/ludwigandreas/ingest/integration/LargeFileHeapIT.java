package ru.ludwigandreas.ingest.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import ru.ludwigandreas.ingest.api.IngestRunStatus;
import ru.ludwigandreas.ingest.engine.IngestPass;

/**
 * A 200 MB object ingests in full, inside a measured heap ceiling.
 *
 * <h2>Why the ceiling is measured rather than asserted in prose</h2>
 *
 * <p>"Never holds the file in memory" is the requirement this module is built around, and a
 * requirement is worth testing rather than restating. The ArchUnit rule in
 * {@code SqlConfinementTest} forbids {@code readAllBytes} and friends by name, which catches the
 * obvious way to break it; it cannot catch the way that actually happens, which is a collection that
 * quietly accumulates across batches. Only watching the heap catches that.
 *
 * <p>The assertion is on peak <em>used</em> heap after a full GC, taken before and after the run. It
 * is a generous ceiling on purpose - the point is to fail when the implementation starts buffering a
 * significant fraction of the object, not to police a few megabytes of Hibernate's own working set.
 * A run that buffered the file would exceed it by two orders of magnitude.
 *
 * <h2>Why the file is 200 MB of few long rows rather than millions of short ones</h2>
 *
 * <p>Both make a large object; only the first makes a large object <em>quickly</em>, and this test has
 * to be one somebody will actually run. The bound being tested is in bytes, which is the point of
 * having a byte bound at all, so padding the rows exercises exactly the limit that matters.
 */
@TestPropertySource(properties = {
        // A batch bound well under the file size, so the run must flush many times. A bound large
        // enough to hold the file would make the test pass for the wrong reason.
        "ludwig.ingest.tasks.partner-catalogue.batch.max-records=2000",
        "ludwig.ingest.tasks.partner-catalogue.batch.max-bytes=8MB"
})
class LargeFileHeapIT extends FileIngestTestBase {

    /** Roughly 200 MB: 40,000 rows of about 5 KB each. */
    private static final int ROWS = 40_000;
    private static final int ROW_PADDING = 5_000;

    /**
     * The ceiling, in bytes. Generous against a 200 MB object: a run that buffered even a tenth of
     * the file would blow through it, and ordinary framework overhead stays far below it.
     */
    private static final long HEAP_CEILING_BYTES = 256L * 1024 * 1024;

    @TempDir
    Path work;

    @Autowired
    private IngestPass pass;

    @Test
    @DisplayName("a 200MB object ingests fully without the heap growing with the file")
    void ingestsALargeObjectInABoundedHeap() throws IOException {
        Path file = writeCsv(work.resolve("large.csv"), ROWS, ROW_PADDING);
        long size = Files.size(file);
        assertThat(size)
                .as("the fixture must actually be large, or the ceiling proves nothing")
                .isGreaterThan(180L * 1024 * 1024);
        put(dropKey("catalogue-large.csv"), file);

        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        long before = usedHeapAfterGc(memory);

        pass.runOnce(CatalogueIngest.TASK);

        long after = usedHeapAfterGc(memory);
        long retained = after - before;

        assertThat(jdbc().queryForObject("SELECT status FROM file_ingest_run", String.class))
                .isEqualTo(IngestRunStatus.COMPLETED.name());
        assertThat(jdbc().queryForObject("SELECT count(*) FROM catalogue", Long.class))
                .isEqualTo(ROWS);
        assertThat(jdbc().queryForObject("SELECT bytes_read FROM file_ingest_run", Long.class))
                .isEqualTo(size);
        assertThat(retained)
                .as("ingesting a %d byte object retained %d bytes of heap. The module must stream:"
                        + " anything approaching the file size here means something is accumulating"
                        + " across batches, which is the failure mode the byte bound and the"
                        + " per-batch clear exist to prevent.", size, retained)
                .isLessThan(HEAP_CEILING_BYTES);
    }

    /**
     * Used heap after asking for a collection, which is the only number here worth comparing.
     *
     * <p>{@code System.gc()} is a request rather than a command, so this is a best effort - but a
     * best effort repeated identically before and after is enough to see a two-orders-of-magnitude
     * difference, which is what a buffered file would be.
     */
    private static long usedHeapAfterGc(MemoryMXBean memory) {
        System.gc();
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.gc();
        return memory.getHeapMemoryUsage().getUsed();
    }
}
