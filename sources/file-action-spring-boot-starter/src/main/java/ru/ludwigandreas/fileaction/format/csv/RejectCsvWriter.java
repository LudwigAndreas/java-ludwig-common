package ru.ludwigandreas.fileaction.format.csv;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import ru.ludwigandreas.fileaction.format.xlsx.write.FormulaGuard;

/**
 * Writes the rejects of a submission as a CSV.
 *
 * <h2>Why this exists next to the annotated workbook</h2>
 *
 * <p>The annotated workbook is for a person. This is for the case where the submission was a CSV in the first
 * place - annotating one means rewriting it, and a CSV has no columns to append to, only fields to add, which
 * changes every row's shape - and for a machine caller that wants the rejects as data.
 *
 * <h2>Excel's CSV, not RFC 4180's</h2>
 *
 * <p>A byte-order mark, a semicolon when the caller's locale uses a comma for decimals, and CRLF endings -
 * the same reasoning {@code export}'s {@code CsvProfile} sets out at length. Without the mark, Excel on
 * Windows opens a UTF-8 file as the system code page and a Cyrillic reject message arrives as mojibake, which
 * makes the one file whose entire purpose is to be read unreadable. This file is downloaded by a person and
 * opened in a spreadsheet essentially always, so the human-facing profile is the only sensible default and
 * there is deliberately no option to choose the other.
 */
public class RejectCsvWriter {

    /** The UTF-8 mark, built from its code point for the reason {@code CsvRowReader} gives. */
    private static final char BYTE_ORDER_MARK = (char) 0xFEFF;

    /**
     * Writes the rejects.
     *
     * @param headers the column headings, already localised
     * @param rows    the rows, each a list of already-localised cell texts in the same order as the headings
     * @param target  where to write; closed by the caller
     * @throws IOException if the file cannot be written
     */
    public void write(List<String> headers, List<List<String>> rows, OutputStream target)
            throws IOException {
        Writer writer = new OutputStreamWriter(target, StandardCharsets.UTF_8);
        writer.write(BYTE_ORDER_MARK);
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(CsvDelimiter.SEMICOLON.character())
                .setRecordSeparator("\r\n")
                .build();
        try (CSVPrinter printer = new CSVPrinter(writer, format)) {
            printer.printRecord(headers.stream().map(FormulaGuard::neutralise).toList());
            for (List<String> row : rows) {
                printer.printRecord(row.stream().map(FormulaGuard::neutralise).toList());
            }
            printer.flush();
        }
    }
}
