package ru.ludwigandreas.fileaction.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.ludwigandreas.fileaction.format.RowReader;
import ru.ludwigandreas.fileaction.format.xlsx.read.XlsxRowReaderFactory;

/**
 * The module's core guarantee, measured: retained heap does not scale with the size of the file.
 *
 * <h2>Why this is tagged out of the default build</h2>
 *
 * <p>Not because it is slow - it takes seconds - but because it is a <em>measurement</em>, and a measurement in a
 * JVM shared with other test classes and instrumented by JaCoCo is not a reliable assertion. It passed on its own
 * and failed in the full suite. A flaky guarantee is worse than an honest tag: a test that goes red for reasons
 * unrelated to the thing it guards is a test somebody eventually deletes, and then the guarantee is gone with it.
 *
 * <p>Run it deliberately, in its own JVM, with a small heap so the JVM itself fails if the reader ever stops
 * streaming:
 *
 * <pre>{@code
 * mvn -pl :file-action-spring-boot-starter verify \
 *     -Dfile-action.test.excluded.groups= -Dit.test=LargeWorkbookHeapMeasurementIT \
 *     -DargLine="-Xmx256m"
 * }</pre>
 *
 * <p>{@code export}'s {@code ReportLoadTest} is arranged the same way, and its POM carries the same reasoning. The
 * deterministic half of this guarantee - that every row is read and the last one is intact - stays in
 * {@code LargeWorkbookHeapIT} and runs on every build.
 *
 * <h2>How the measurement works, and what it is honest about</h2>
 *
 * <p>It does not assert an absolute number. It asserts the shape: that retained heap after reading a hundred
 * thousand rows is not proportional to the file, by comparing the growth over a small read with the growth over a
 * large one. A fixed byte ceiling would be tuned to whichever machine first ran it and would go red on a CI agent
 * with a different collector.
 */
@Tag("measurement")
class LargeWorkbookHeapMeasurementIT {

    /** A small read, for the baseline the large one is compared against. */
    private static final int SMALL_ROWS = 1_000;

    /**
     * How much more heap the large read may retain than the small one.
     *
     * <p>Four, against a hundredfold difference in row count. A streaming read's two numbers differ by the styles
     * and shared-strings tables, which grow with the number of <em>distinct</em> strings rather than with the row
     * count; a DOM read's differ by a factor near a hundred.
     */
    private static final double TOLERATED_GROWTH_FACTOR = 4.0;

    /** How many rows the DOM comparison uses - enough to be unmistakable, small enough not to exhaust the JVM. */
    private static final int DOM_COMPARISON_ROWS = 20_000;

    /** How many times more a DOM read must retain for the measurement to count as discriminating. */
    private static final int DOM_DISCRIMINATION_FACTOR = 5;

    @TempDir
    Path temp;

    @Test
    @DisplayName("reading a hundred thousand rows retains heap in proportion to the window, not to the file")
    void retainedHeapDoesNotScaleWithTheFile() throws IOException {
        long smallGrowth = readAndMeasure(workbook("small.xlsx", SMALL_ROWS));
        long largeGrowth = readAndMeasure(workbook("large.xlsx", LargeWorkbooks.LARGE_ROWS));

        assertThat(largeGrowth)
                .as("a hundredfold more rows retained %d bytes against %d - a DOM read fails this by two orders of"
                        + " magnitude, which is the whole reason the reader is SAX", largeGrowth, smallGrowth)
                .isLessThan((long) (Math.max(smallGrowth, 1) * TOLERATED_GROWTH_FACTOR));
    }

    @Test
    @DisplayName("the measurement discriminates: a DOM read of the same file retains far more")
    void theMeasurementWouldCatchADomRead() throws IOException {
        // The proof that the assertion above is capable of failing. A budget that has never been seen red is a
        // budget nobody should trust - the same standard the repository applies to its architecture tests.
        //
        // A DOM read is legal in this one class and nowhere else in the module: PoiConfinementTest imports src/main
        // only, deliberately, because the forbidden API has to be exercised somewhere to prove why it is forbidden.
        Path file = workbook("dom.xlsx", DOM_COMPARISON_ROWS);

        long streaming = readAndMeasure(file);
        long dom = readWithDomAndMeasure(file);

        assertThat(dom)
                .as("a DOM read retained %d bytes where the SAX read retained %d; if these were ever comparable, the"
                        + " budget assertion would be measuring nothing", dom, streaming)
                .isGreaterThan(Math.max(streaming, 1) * DOM_DISCRIMINATION_FACTOR);
    }

    /**
     * Reads a workbook and returns how much heap is still retained afterwards.
     *
     * <p>The reader is closed and its reference dropped before measuring, so what is measured is what the read
     * <em>retained</em> rather than what it touched.
     */
    private long readAndMeasure(Path file) throws IOException {
        Runtime runtime = Runtime.getRuntime();
        settle();
        long before = runtime.totalMemory() - runtime.freeMemory();

        long rows = 0;
        try (RowReader reader = new XlsxRowReaderFactory()
                .open(file, LargeWorkbooks.BINDING, LargeWorkbookHeapIT.budget())) {
            Counter counter = new Counter();
            reader.forEachRow(row -> {
                // Examined and dropped. Collecting the rows would measure the test's own list rather than the
                // reader, which is the mistake that makes a test like this pass for the wrong reason.
                counter.seen += row.cell("SKU").trimmedText() == null ? 0 : 1;
                return true;
            });
            rows = counter.seen;
        }
        assertThat(rows).isPositive();
        settle();
        return Math.max(runtime.totalMemory() - runtime.freeMemory() - before, 0);
    }

    /** Reads the same workbook through the DOM reader, for the comparison above only. */
    private long readWithDomAndMeasure(Path file) throws IOException {
        Runtime runtime = Runtime.getRuntime();
        settle();
        long before = runtime.totalMemory() - runtime.freeMemory();
        long cells = 0;
        try (InputStream in = Files.newInputStream(file);
                org.apache.poi.ss.usermodel.Workbook workbook =
                        new org.apache.poi.xssf.usermodel.XSSFWorkbook(in)) {
            for (org.apache.poi.ss.usermodel.Row row : workbook.getSheetAt(0)) {
                cells += row.getLastCellNum();
            }
            assertThat(cells).isPositive();
            settle();
            // Measured while the workbook is still open, because what a DOM read costs is what it retains for as
            // long as you hold it - measuring after the close would measure nothing.
            return Math.max(runtime.totalMemory() - runtime.freeMemory() - before, 0);
        }
    }

    // SUPPRESS CHECKSTYLE ID ExplicitGc - a heap measurement is the one place a collection request is meaningful,
    // and the assertion is a ratio precisely because the request is advisory.
    private static void settle() {
        System.gc();
        System.gc();
    }

    private Path workbook(String name, int rows) throws IOException {
        Path file = temp.resolve(name);
        LargeWorkbooks.write(file, rows);
        return file;
    }

    /** A mutable counter, because a lambda cannot assign to a local. */
    private static final class Counter {
        private long seen;
    }
}
