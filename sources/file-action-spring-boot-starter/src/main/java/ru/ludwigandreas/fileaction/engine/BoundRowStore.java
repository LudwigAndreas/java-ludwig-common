package ru.ludwigandreas.fileaction.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * The bound rows of one submission, as newline-delimited JSON.
 *
 * <h2>Why the bound rows are written down at all</h2>
 *
 * <p>Three things fall out of one decision, which is why the artifact exists rather than the rows being applied
 * as they are read.
 *
 * <p><b>A {@code CONFIRM}-mode action must apply what it showed the user.</b> Validation and confirmation are
 * two requests, minutes apart. Re-reading and re-binding the workbook on confirmation would re-run every
 * coercion against whatever the reference data says <em>now</em>, so a submission could apply something other
 * than the preview the user approved - silently, and only when something changed in between, which is the
 * hardest possible case to reproduce.
 *
 * <p><b>A {@code DocumentHandler} needs a {@code Stream}.</b> It applies every row as one business fact, so it
 * has to see them all - and the reader is a push-based SAX parse. Reading the artifact back gives a genuinely
 * lazy stream with no thread and no buffer, which neither the reader nor a collected list can.
 *
 * <p><b>{@code DIRECT} and {@code CONFIRM} become one code path.</b> Both bind to the artifact and apply from
 * it; the only difference is whether the artifact is uploaded and waited on, or applied and discarded. A
 * conditional that skipped the artifact for {@code DIRECT} would be a second apply path that only the less
 * cautious mode exercises.
 *
 * <h2>Why NDJSON rather than one JSON document</h2>
 *
 * <p>A single array has to be fully parsed before its first element is available, and fully built before it can
 * be written, so it would put the whole file in the heap at both ends - the one thing this module does not do.
 * One object per line is written and read a row at a time.
 */
public class BoundRowStore {

    private final ObjectMapper mapper;

    /**
     * Uses the application's own mapper.
     *
     * @param mapper the mapper, which must be able to write the row record types bindings declare. Records
     *               need no configuration for this; a row type carrying something exotic is the author's
     *               problem and fails loudly on the first row rather than silently later
     */
    public BoundRowStore(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Appends rows one at a time to a local artifact. */
    public final class Writer implements AutoCloseable {

        private final BufferedWriter out;
        private long written;

        private Writer(BufferedWriter out) {
            this.out = out;
        }

        /**
         * Writes one bound row with its address.
         *
         * @param row the row and where it came from
         * @throws IOException if it cannot be written
         */
        public void write(BoundRow<?> row) throws IOException {
            // writeValueAsString rather than writeValue, so that a row which cannot be serialised fails
            // before anything has been appended - a half-written line would make the whole artifact
            // unreadable, and the row that broke it unidentifiable.
            out.write(mapper.writeValueAsString(row));
            out.write('\n');
            written++;
        }

        /** How many rows have been written. */
        public long count() {
            return written;
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }

    /**
     * Opens a writer over a local file.
     *
     * @param target where to write
     * @return the writer; close it
     * @throws IOException if the file cannot be created
     */
    public Writer writer(Path target) throws IOException {
        return new Writer(Files.newBufferedWriter(target, StandardCharsets.UTF_8));
    }

    /**
     * Copies a local artifact to a stream, for upload to the object store.
     *
     * @param source the local artifact
     * @param target the stream
     * @throws IOException if the copy fails
     */
    public void copyTo(Path source, OutputStream target) throws IOException {
        Files.copy(source, target);
    }

    /**
     * Streams the rows back, lazily.
     *
     * <p>The returned stream holds an open reader and <strong>must be closed</strong>. Consume it in a
     * try-with-resources, as {@code object-storage}'s {@code list} requires for the same reason.
     *
     * @param source  the artifact
     * @param rowType the row record type
     * @param <R>     the row type
     * @return a lazy stream over the rows
     * @throws IOException if the artifact cannot be opened
     */
    public <R> Stream<BoundRow<R>> stream(Path source, Class<R> rowType) throws IOException {
        BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8);
        return reader.lines()
                .filter(line -> !line.isBlank())
                .map(line -> read(line, rowType))
                .onClose(() -> closeQuietly(reader));
    }

    /**
     * Streams the rows back from an object-store stream.
     *
     * @param source  the content
     * @param rowType the row record type
     * @param <R>     the row type
     * @return a lazy stream; close it
     */
    public <R> Stream<BoundRow<R>> stream(InputStream source, Class<R> rowType) {
        BufferedReader reader = new BufferedReader(
                new java.io.InputStreamReader(source, StandardCharsets.UTF_8));
        return reader.lines()
                .filter(line -> !line.isBlank())
                .map(line -> read(line, rowType))
                .onClose(() -> closeQuietly(reader));
    }

    private <R> BoundRow<R> read(String line, Class<R> rowType) {
        try {
            return mapper.readValue(line, mapper.getTypeFactory()
                    .constructParametricType(BoundRow.class, rowType));
        } catch (IOException malformed) {
            // The artifact is written by this module, so a line it cannot read back is a defect here rather
            // than bad input - which is why this is unchecked and loud instead of a row-level reject.
            throw new UncheckedIOException(
                    "a bound-row artifact line could not be read back as " + rowType.getName(), malformed);
        }
    }

    private static void closeQuietly(BufferedReader reader) {
        try {
            reader.close();
        } catch (IOException ignored) {
            // Closing a reader whose stream is already finished has nothing to report, and throwing from a
            // stream's close handler would replace whatever the consumer was doing with this.
            return;
        }
    }
}
