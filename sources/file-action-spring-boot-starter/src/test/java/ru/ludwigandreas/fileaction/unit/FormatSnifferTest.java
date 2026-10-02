package ru.ludwigandreas.fileaction.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.fileaction.exception.FileActionProblemCodes;
import ru.ludwigandreas.fileaction.exception.FileRejectedException;
import ru.ludwigandreas.fileaction.format.FormatSniffer;
import ru.ludwigandreas.fileaction.format.SourceFormat;

/** What the content says it is, regardless of what the filename says. */
class FormatSnifferTest {

    private final FormatSniffer sniffer = new FormatSniffer();

    @Test
    @DisplayName("a ZIP container sniffs as xlsx")
    void zipIsXlsx() throws IOException {
        assertThat(sniff(zipBytes(), "whatever.txt")).isEqualTo(SourceFormat.XLSX);
    }

    @Test
    @DisplayName("delimited text sniffs as csv")
    void textIsCsv() throws IOException {
        byte[] csv = "SKU;Qty\nA-1;3\n".getBytes(StandardCharsets.UTF_8);
        assertThat(sniff(csv, "orders.csv")).isEqualTo(SourceFormat.CSV);
    }

    @Test
    @DisplayName("a single-column csv with no delimiter anywhere still sniffs as csv")
    void singleColumnIsStillCsv() throws IOException {
        byte[] csv = "SKU\nA-1\nA-2\n".getBytes(StandardCharsets.UTF_8);
        assertThat(sniff(csv, "skus.csv")).isEqualTo(SourceFormat.CSV);
    }

    @Test
    @DisplayName("a csv renamed to .xlsx is read as a csv - the declared name is not trusted")
    void declaredNameIsNotTrusted() throws IOException {
        byte[] csv = "SKU,Qty\nA-1,3\n".getBytes(StandardCharsets.UTF_8);
        assertThat(sniff(csv, "orders.xlsx")).isEqualTo(SourceFormat.CSV);
    }

    @Test
    @DisplayName("an xlsx renamed to .csv is read as an xlsx")
    void soIsTheOtherDirection() throws IOException {
        assertThat(sniff(zipBytes(), "orders.csv")).isEqualTo(SourceFormat.XLSX);
    }

    @Test
    @DisplayName("a legacy .xls is detected and refused with the message that helps")
    void legacyXlsIsNamedRatherThanMerelyUnsupported() {
        byte[] ole2 = {(byte) 0xD0, (byte) 0xCF, (byte) 0x11, (byte) 0xE0,
                       (byte) 0xA1, (byte) 0xB1, (byte) 0x1A, (byte) 0xE1, 0, 0, 0, 0};

        assertThatThrownBy(() -> sniff(ole2, "orders.xls"))
                .isInstanceOf(FileRejectedException.class)
                .hasFieldOrPropertyWithValue("code", FileActionProblemCodes.LEGACY_XLS);
    }

    @Test
    @DisplayName("binary that is neither is refused as an unsupported format")
    void binaryIsRefused() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

        assertThatThrownBy(() -> sniff(png, "chart.png"))
                .isInstanceOf(FileRejectedException.class)
                .hasFieldOrPropertyWithValue("code", FileActionProblemCodes.UNSUPPORTED_FORMAT);
    }

    @Test
    @DisplayName("an empty upload is refused as empty, not as an unsupported format")
    void emptyIsItsOwnMessage() {
        assertThatThrownBy(() -> sniff(new byte[0], "orders.csv"))
                .isInstanceOf(FileRejectedException.class)
                .hasFieldOrPropertyWithValue("code", FileActionProblemCodes.EMPTY);
    }

    @Test
    @DisplayName("the stream is left at byte zero, so the reader afterwards sees the whole file")
    void leavesTheStreamWhereItFoundIt() throws IOException {
        byte[] csv = "SKU,Qty\nA-1,3\n".getBytes(StandardCharsets.UTF_8);
        InputStream stream = FormatSniffer.sniffing(new ByteArrayInputStream(csv));

        sniffer.sniff(stream, "orders.csv");

        assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo("SKU,Qty\nA-1,3\n");
    }

    private SourceFormat sniff(byte[] content, String filename) throws IOException {
        return sniffer.sniff(FormatSniffer.sniffing(new ByteArrayInputStream(content)), filename);
    }

    private static byte[] zipBytes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
