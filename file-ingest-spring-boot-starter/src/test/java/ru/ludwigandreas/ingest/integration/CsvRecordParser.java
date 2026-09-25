package ru.ludwigandreas.ingest.integration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.NoSuchElementException;
import ru.ludwigandreas.ingest.api.CheckpointKind;
import ru.ludwigandreas.ingest.api.ParsedRecord;
import ru.ludwigandreas.ingest.api.RecordParser;

/**
 * A line-delimited CSV parser, of the shape a service would actually write.
 *
 * <p>Deliberately not a fixture that cheats: it reads one line at a time, reports the byte offsets it
 * actually consumed, and hands back a failed record rather than throwing when a line does not have
 * the right number of fields. The integration suite's claims about resumption and about bounded
 * memory would all be vacuous against a parser that read the file into a list first.
 */
class CsvRecordParser implements RecordParser<CsvRecordParser.Row> {

    /** One product row. */
    record Row(String sku, String name, long priceCents) {
    }

    private static final int FIELDS = 3;

    @Override
    public CheckpointKind checkpointKind() {
        return CheckpointKind.BYTE_OFFSET;
    }

    @Override
    public Iterator<ParsedRecord<Row>> parse(InputStream stream, long startOffset, long startOrdinal) {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8), 64 * 1024);
        return new Iterator<>() {

            private long offset = startOffset;
            private long ordinal = startOrdinal;
            private String pending;
            private boolean drained;

            @Override
            public boolean hasNext() {
                if (pending != null) {
                    return true;
                }
                if (drained) {
                    return false;
                }
                try {
                    pending = reader.readLine();
                } catch (IOException e) {
                    throw new IllegalStateException("Could not read the CSV stream", e);
                }
                if (pending == null) {
                    drained = true;
                    return false;
                }
                return true;
            }

            @Override
            public ParsedRecord<Row> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException("No more CSV records");
                }
                String line = pending;
                pending = null;
                // The +1 is the newline the reader consumed and did not return. Getting this wrong is
                // the off-by-one that makes a resume start one byte into a record, which is why the
                // offsets are the parser's job: only it knows where the delimiter was.
                long start = offset;
                long end = start + line.getBytes(StandardCharsets.UTF_8).length + 1;
                offset = end;
                long index = ordinal++;

                String[] fields = line.split(",", -1);
                if (fields.length != FIELDS) {
                    return ParsedRecord.failed(index, start, end, line,
                            new IllegalArgumentException("expected " + FIELDS + " fields, found "
                                    + fields.length));
                }
                try {
                    return ParsedRecord.parsed(
                            new Row(fields[0], fields[1], Long.parseLong(fields[2])),
                            index, start, end, line);
                } catch (NumberFormatException e) {
                    return ParsedRecord.failed(index, start, end, line, e);
                }
            }
        };
    }
}
