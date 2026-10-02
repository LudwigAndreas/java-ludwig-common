package ru.ludwigandreas.fileaction.format.csv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import ru.ludwigandreas.fileaction.api.ColumnBinding;
import ru.ludwigandreas.fileaction.api.RowBinding;
import ru.ludwigandreas.fileaction.exception.FileActionProblemCodes;
import ru.ludwigandreas.fileaction.exception.FileRejectedException;
import ru.ludwigandreas.fileaction.format.CellValue;
import ru.ludwigandreas.fileaction.format.RawRow;
import ru.ludwigandreas.fileaction.format.ReadBudget;
import ru.ludwigandreas.fileaction.format.RowReader;
import ru.ludwigandreas.fileaction.format.RowVisitor;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * Reads a delimited text file into rows, one row at a time.
 *
 * <h2>Why commons-csv rather than splitting on the delimiter</h2>
 *
 * <p>Three things a {@code split} gets wrong, all of which arrive as a user's file rather than as a test:
 * a quoted field containing the delimiter, a quoted field containing a newline, and a doubled quote
 * inside a quoted field. A product description with a comma in it is the first of those and is not an
 * edge case. commons-csv is a lexer, it streams, and it has had those three right for fifteen years.
 *
 * <h2>Every CSV cell is text, and that is the point of {@code CellValue}</h2>
 *
 * <p>A CSV has no types - {@code 1 234,56} is five characters and nothing in the file says it is a
 * number - so every cell this reader produces is {@link CellValue#ofText}, and turning it into a
 * {@code BigDecimal} or a {@code LocalDate} is the coercion layer's job, in the caller's locale. This is
 * the opposite of the XLSX reader, where the number is in the file and converting it to text would
 * <em>create</em> the ambiguity. Both readers feed the same coercion, which is why {@code CellValue}
 * carries both shapes.
 */
public final class CsvRowReader implements RowReader {

    private final CSVParser parser;
    private final Iterator<CSVRecord> records;
    private final List<String> headers;
    private final Map<String, Integer> indexByHeader;
    private final ReadBudget budget;
    private int rowsRead;

    private CsvRowReader(CSVParser parser, List<String> headers, Map<String, Integer> indexByHeader,
                        ReadBudget budget) {
        this.parser = parser;
        this.records = parser.iterator();
        this.headers = List.copyOf(headers);
        this.indexByHeader = Map.copyOf(indexByHeader);
        this.budget = budget;
    }

    /**
     * Opens a reader over delimited content.
     *
     * @param content the file's bytes
     * @param binding what the rows bind to
     * @param budget  the ceilings this read may not exceed
     * @return the reader
     */
    static CsvRowReader open(InputStream content, RowBinding<?> binding, ReadBudget budget) {
        try {
            Reader reader = new BufferedReader(
                    new InputStreamReader(content, StandardCharsets.UTF_8));
            String headerLine = readHeaderLine(reader);
            CsvDelimiter delimiter = CsvDelimiterSniffer.sniff(headerLine);
            List<String> headers = parseHeaderLine(headerLine, delimiter);
            Map<String, Integer> indexByHeader = resolve(headers, binding);
            CSVParser parser = CSVFormat.DEFAULT.builder()
                    .setDelimiter(delimiter.character())
                    .setIgnoreSurroundingSpaces(true)
                    .setIgnoreEmptyLines(true)
                    .setAllowMissingColumnNames(true)
                    .setTrailingDelimiter(false)
                    .build()
                    .parse(reader);
            return new CsvRowReader(parser, headers, indexByHeader, budget);
        } catch (IOException unreadable) {
            throw new FileRejectedException(ProblemStatus.INVALID, FileActionProblemCodes.UNREADABLE,
                    unreadable, "csv");
        }
    }

    /**
     * Reads the first line, removing a byte-order mark if there is one.
     *
     * <p>The mark is the single most common reason a CSV's first column header reads as a mark followed by
     * {@code SKU} rather than as {@code SKU}, so the required column appears missing on a file that looks
     * correct in every editor. Excel writes one on every UTF-8 CSV it saves, so this is the common case
     * rather than a curiosity.
     *
     * <p>Read by hand rather than by letting commons-csv take the header, because the delimiter has to be
     * sniffed from this line before a parser can be configured at all - and a parser configured with the
     * wrong delimiter has already consumed the line by the time that is discoverable.
     */
    private static String readHeaderLine(Reader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = reader.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                line.append((char) c);
            }
        }
        if (line.length() > 0 && line.charAt(0) == BYTE_ORDER_MARK) {
            line.deleteCharAt(0);
        }
        return line.toString();
    }

    /**
     * The UTF-8 byte-order mark as a decoded character.
     *
     * <p>Built from its code point rather than written as a character literal, for the reason
     * {@code export}'s {@code CsvProfile} gives: the escape is processed before the source is lexed, so
     * the character would sit invisibly in this file and Checkstyle would fail on a line whose problem
     * nobody could see.
     */
    private static final char BYTE_ORDER_MARK = (char) 0xFEFF;

    private static List<String> parseHeaderLine(String headerLine, CsvDelimiter delimiter) {
        try (CSVParser headerParser = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter.character())
                .setIgnoreSurroundingSpaces(true)
                .build()
                .parse(new java.io.StringReader(headerLine))) {
            Iterator<CSVRecord> iterator = headerParser.iterator();
            if (!iterator.hasNext()) {
                throw new FileRejectedException(ProblemStatus.INVALID,
                        FileActionProblemCodes.NO_HEADER_ROW, "csv");
            }
            CSVRecord header = iterator.next();
            List<String> headers = new ArrayList<>(header.size());
            for (String value : header) {
                headers.add(value);
            }
            return headers;
        } catch (IOException unreadable) {
            throw new FileRejectedException(ProblemStatus.INVALID, FileActionProblemCodes.UNREADABLE,
                    unreadable, "csv");
        }
    }

    /**
     * Maps each declared column to the position its header was found at.
     *
     * <p>This is the one place a column index exists. Everything downstream speaks header names, which is
     * what makes inserting a column harmless.
     */
    private static Map<String, Integer> resolve(List<String> headers, RowBinding<?> binding) {
        Map<String, Integer> resolved = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            final int index = i;
            Optional<ColumnBinding> column = binding.columnForHeader(headers.get(i));
            // First occurrence wins: a file with the same header twice is odd but not broken, and taking
            // the later one would mean the value shown to the user came from a column they cannot see.
            column.ifPresent(bound -> resolved.putIfAbsent(bound.header(), index));
        }
        return resolved;
    }

    @Override
    public String sheet() {
        return null;
    }

    @Override
    public List<String> headers() {
        return headers;
    }

    @Override
    public void forEachRow(RowVisitor visitor) {
        while (records.hasNext()) {
            if (!visitor.visit(toRow(records.next()))) {
                return;
            }
        }
    }

    private RawRow toRow(CSVRecord record) {
        rowsRead++;
        if (rowsRead > budget.maxRows()) {
            throw new FileRejectedException(ProblemStatus.PAYLOAD_TOO_LARGE,
                    FileActionProblemCodes.TOO_MANY_ROWS, budget.maxRows());
        }
        List<String> problems = new ArrayList<>(0);
        if (record.size() != headers.size()) {
            // Carried rather than thrown: the row still reaches the reject report with its address, which
            // is what lets the user find the line with the stray delimiter in it.
            problems.add(FileActionProblemCodes.RAGGED_ROW);
        }
        Map<String, CellValue> cells = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : indexByHeader.entrySet()) {
            int index = entry.getValue();
            if (index >= record.size()) {
                continue;
            }
            String value = record.get(index);
            if (value != null && value.length() > budget.maxCellCharacters()) {
                problems.add(FileActionProblemCodes.CELL_NOT_COERCIBLE);
                continue;
            }
            CellValue cell = CellValue.ofText(value);
            if (!cell.isEmpty()) {
                cells.put(entry.getKey(), cell);
            }
        }
        // The record number counts records rather than physical lines, which is what a user means by "row"
        // even when a quoted field spans two lines. Plus one for the header, which this reader consumed
        // before the parser was created and which the parser therefore does not count.
        int displayedRow = (int) Math.min(record.getRecordNumber() + 1, Integer.MAX_VALUE);
        return new RawRow(null, displayedRow, cells, problems);
    }

    @Override
    public void close() {
        try {
            parser.close();
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
