package ru.ludwigandreas.fileaction.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.ludwigandreas.fileaction.format.RawRow;
import ru.ludwigandreas.fileaction.format.ReadBudget;
import ru.ludwigandreas.fileaction.format.RowReader;
import ru.ludwigandreas.fileaction.format.xlsx.read.XlsxRowReaderFactory;

/**
 * A workbook at the module's declared ceiling is read in full, correctly, every row of it.
 *
 * <h2>Why the heap measurement is not in this class</h2>
 *
 * <p>It is in {@code LargeWorkbookHeapMeasurementIT}, behind a tag, and this class keeps only what is deterministic.
 * A heap ratio measured in a JVM shared with the other integration tests and instrumented by JaCoCo is not a
 * reliable assertion: it passed on its own and failed in the full suite, which is the definition of a flaky test,
 * and a flaky guarantee is worse than an honest tag. {@code export} puts its load test behind a tag for the same
 * reason, and its POM records the same argument.
 *
 * <h2>What these two cases are still worth</h2>
 *
 * <p>They are what a streaming reader has to get right and a broken one would not: every row of a hundred thousand
 * is handed over, and the last of them carries the values the fixture wrote. A reader that bounded its memory by
 * quietly stopping, or by dropping rows under pressure, fails here - with no measurement involved and no flakiness.
 */
class LargeWorkbookHeapIT {

    @TempDir
    Path temp;

    @Test
    @DisplayName("every row of a hundred-thousand-row workbook is read, so no bound is achieved by stopping")
    void everyRowIsRead() throws IOException {
        Path file = workbook("complete.xlsx");
        AtomicLong seen = new AtomicLong();

        try (RowReader reader = new XlsxRowReaderFactory()
                .open(file, LargeWorkbooks.BINDING, budget())) {
            reader.forEachRow(row -> {
                seen.incrementAndGet();
                return true;
            });
        }

        assertThat(seen.get())
                .as("a bounded heap achieved by reading fewer rows would be no achievement at all")
                .isEqualTo(LargeWorkbooks.LARGE_ROWS);
    }

    @Test
    @DisplayName("the last row read carries the values the fixture wrote, so nothing is silently dropped")
    void theLastRowIsIntact() throws IOException {
        Path file = workbook("intact.xlsx");
        List<RawRow> last = new ArrayList<>(1);

        try (RowReader reader = new XlsxRowReaderFactory()
                .open(file, LargeWorkbooks.BINDING, budget())) {
            reader.forEachRow(row -> {
                last.clear();
                last.add(row);
                return true;
            });
        }

        assertThat(last).singleElement().satisfies(row -> {
            assertThat(row.cell("SKU").trimmedText()).isEqualTo("A-" + LargeWorkbooks.LARGE_ROWS);
            assertThat(row.cell("Qty").number().intValue())
                    .isEqualTo(LargeWorkbooks.LARGE_ROWS % 97);
            assertThat(row.displayedRow()).isEqualTo(LargeWorkbooks.LARGE_ROWS + 1);
        });
    }

    private Path workbook(String name) throws IOException {
        Path file = temp.resolve(name);
        LargeWorkbooks.write(file, LargeWorkbooks.LARGE_ROWS);
        return file;
    }

    static ReadBudget budget() {
        return new ReadBudget(64L * 1024 * 1024, LargeWorkbooks.LARGE_ROWS, 1_000, 0.0001d,
                1024L * 1024 * 1024, 64L * 1024 * 1024, 32_000, Duration.ofMinutes(5));
    }
}
