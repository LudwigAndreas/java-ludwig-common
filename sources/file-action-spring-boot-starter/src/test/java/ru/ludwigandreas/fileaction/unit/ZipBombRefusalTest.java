package ru.ludwigandreas.fileaction.unit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.ludwigandreas.fileaction.exception.FileActionProblemCodes;
import ru.ludwigandreas.fileaction.exception.FileRejectedException;
import ru.ludwigandreas.fileaction.format.xlsx.read.ArchiveInspector;

/**
 * The container is judged before a single byte of XML is parsed.
 *
 * <p>Every case here is decided from the ZIP's central directory, so none of these files is ever
 * decompressed - which is the point: by the time a SAX handler could notice, the inflater has already
 * produced the bytes.
 */
class ZipBombRefusalTest {

    @TempDir
    Path temp;

    private final ArchiveInspector inspector = new ArchiveInspector();

    @Test
    @DisplayName("an entry that inflates far beyond the ratio floor is refused")
    void refusesAHighRatioEntry() throws IOException {
        // A megabyte of zeros compresses to a few hundred bytes: a ratio around 0.0005.
        Path bomb = zip(entry("xl/worksheets/sheet1.xml", new byte[1024 * 1024]));

        assertThatThrownBy(() -> inspector.inspect(bomb, ReadBudgets.withMinInflateRatio(0.01d)))
                .isInstanceOf(FileRejectedException.class)
                .hasFieldOrPropertyWithValue("code", FileActionProblemCodes.SUSPICIOUS_ARCHIVE);
    }

    @Test
    @DisplayName("an ordinary workbook's parts pass the ratio floor")
    void acceptsOrdinaryCompression() throws IOException {
        Path file = temp.resolve("orders.xlsx");
        Workbooks.write(file, "Orders", List.of("SKU", "Qty"), List.of(List.of("A-1", 3)));

        assertThatCode(() -> inspector.inspect(file, ReadBudgets.generous()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("too many entries is refused before any of them is opened")
    void refusesTooManyEntries() throws IOException {
        ZipEntrySpec[] entries = new ZipEntrySpec[20];
        for (int i = 0; i < entries.length; i++) {
            entries[i] = entry("part" + i + ".xml", "<a/>".getBytes(StandardCharsets.UTF_8));
        }
        Path many = zip(entries);

        assertThatThrownBy(() -> inspector.inspect(many, ReadBudgets.withMaxArchiveEntries(5)))
                .isInstanceOf(FileRejectedException.class)
                .hasFieldOrPropertyWithValue("code", FileActionProblemCodes.SUSPICIOUS_ARCHIVE);
    }

    @Test
    @DisplayName("a bomb spread thinly across entries is caught by the total-inflation ceiling")
    void refusesTotalInflation() throws IOException {
        ZipEntrySpec[] entries = new ZipEntrySpec[8];
        for (int i = 0; i < entries.length; i++) {
            entries[i] = entry("part" + i + ".xml", new byte[256 * 1024]);
        }
        Path spread = zip(entries);

        assertThatThrownBy(() -> inspector.inspect(spread,
                ReadBudgets.withMaxUncompressedBytes(512L * 1024)))
                .as("no single entry's ratio need look alarming for the total to be a denial of service")
                .isInstanceOf(FileRejectedException.class)
                .hasFieldOrPropertyWithValue("code", FileActionProblemCodes.SUSPICIOUS_ARCHIVE);
    }

    @Test
    @DisplayName("an oversized shared-strings table is refused, because POI materialises it whatever we do")
    void refusesAnOversizedSharedStringsTable() throws IOException {
        Path file = zip(entry("xl/sharedStrings.xml", new byte[2 * 1024 * 1024]));

        assertThatThrownBy(() -> inspector.inspect(file,
                ReadBudgets.withMaxSharedStringBytes(64L * 1024)))
                .isInstanceOf(FileRejectedException.class)
                .hasFieldOrPropertyWithValue("code", FileActionProblemCodes.SUSPICIOUS_ARCHIVE);
    }

    @Test
    @DisplayName("a workbook referencing another file is refused rather than silently importing stale values")
    void refusesExternalLinks() throws IOException {
        Path file = zip(entry("xl/workbook.xml", "<workbook/>".getBytes(StandardCharsets.UTF_8)),
                entry("xl/externalLinks/externalLink1.xml", "<x/>".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> inspector.inspect(file, ReadBudgets.generous()))
                .isInstanceOf(FileRejectedException.class)
                .hasFieldOrPropertyWithValue("code", FileActionProblemCodes.EXTERNAL_REFERENCES);
    }

    @Test
    @DisplayName("something that is not a ZIP at all is refused as unreadable")
    void refusesNonZip() throws IOException {
        Path file = temp.resolve("not.xlsx");
        Files.write(file, "this is not a zip".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> inspector.inspect(file, ReadBudgets.generous()))
                .isInstanceOf(FileRejectedException.class)
                .hasFieldOrPropertyWithValue("code", FileActionProblemCodes.UNREADABLE);
    }

    private record ZipEntrySpec(String name, byte[] content) {
    }

    private static ZipEntrySpec entry(String name, byte[] content) {
        return new ZipEntrySpec(name, content);
    }

    private Path zip(ZipEntrySpec... entries) throws IOException {
        Path file = temp.resolve("archive-" + System.nanoTime() + ".xlsx");
        try (OutputStream out = Files.newOutputStream(file);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (ZipEntrySpec spec : entries) {
                zip.putNextEntry(new ZipEntry(spec.name()));
                zip.write(spec.content());
                zip.closeEntry();
            }
        }
        return file;
    }
}
