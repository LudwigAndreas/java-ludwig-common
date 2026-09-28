package ru.ludwigandreas.ingest.api;

import java.io.InputStream;
import java.util.Iterator;

/**
 * Bytes to records. One half of what the author of an ingest writes; the other is
 * {@link RecordApplier}.
 *
 * <h2>The contract that matters: nothing is materialised</h2>
 *
 * <p>An implementation is handed a stream and must return an {@link Iterator} that reads from it
 * lazily. It must not call {@code readAllBytes}, must not call {@code Files.readAllLines}, and must
 * not build a {@code List} of the object's records. A parser that does any of those turns a module
 * designed around a bounded heap into one that needs a heap the size of the file, and the failure
 * arrives in production on the day a partner sends a bigger drop than usual - not in any test.
 *
 * <p>This module enforces what it can: an ArchUnit rule in its own test suite forbids those calls
 * inside the module, and the integration suite runs a 200 MB file under a measured heap ceiling. It
 * cannot enforce them inside a parser a service writes, which is why the requirement is stated here,
 * at the seam, rather than only in the README.
 *
 * <h2>The parser owns the offsets, because only it knows where a record ended</h2>
 *
 * <p>Each {@link ParsedRecord} carries the byte offsets of the record it describes. The engine cannot
 * compute them: it hands over a decompressing stream, so the bytes the parser consumes are not the
 * bytes in the object, and the delimiter that ends a record is the parser's business. Those offsets
 * are what a byte-offset checkpoint advances to, so a parser that reports them wrongly produces a
 * resume that lands mid-record.
 *
 * <h2>A malformed record is returned, not thrown</h2>
 *
 * <p>Use {@link ParsedRecord#failed}. Throwing ends the run at the first bad line, which is a policy
 * ({@code fail-fast}) rather than a mechanism, and the engine applies the task's policy for itself.
 * Throwing is still the right answer for a failure that is not about one record - a truncated gzip
 * stream, an unreadable header - because there is no record to quarantine and no way to continue.
 *
 * @param <R> the record type this parser produces, which {@link RecordApplier} consumes
 */
public interface RecordParser<R> {

    /**
     * Which checkpoint shape this parser can produce.
     *
     * <p>Declared by the parser rather than configured, because it is a property of the format and
     * getting it wrong is a correctness bug rather than a tuning mistake. A line-delimited parser
     * returns {@link CheckpointKind#BYTE_OFFSET} and its runs resume with a ranged GET; anything else
     * returns {@link CheckpointKind#RECORD_ORDINAL} and its runs resume by re-reading and skipping.
     *
     * @return the shape
     */
    CheckpointKind checkpointKind();

    /**
     * Reads records out of a stream.
     *
     * <p>The stream is positioned at {@code startOffset} bytes into the <em>uncompressed</em> content
     * and is already decompressed if the task is configured for it. The parser closes nothing: the
     * engine owns the stream and closes it, including when this iterator is abandoned partway through,
     * which is what a failed lease renewal does.
     *
     * @param stream      the content, already decompressed and already positioned
     * @param startOffset the uncompressed byte offset the stream begins at, so the parser can report
     *                    absolute offsets rather than offsets relative to the resume point
     * @param startOrdinal the record ordinal the stream begins at, for the same reason
     * @return a lazy iterator; must not read ahead further than it has to
     */
    Iterator<ParsedRecord<R>> parse(InputStream stream, long startOffset, long startOrdinal);

    /**
     * How many records to skip before the first one is handed back, when resuming a
     * {@link CheckpointKind#RECORD_ORDINAL} run.
     *
     * <p>Defaults to the engine doing the skipping by pulling and discarding, which is correct for
     * every parser. A parser over a format with an index - Parquet's row groups, say - can override
     * it to seek instead, which is the one place the ordinal shape can be made cheap.
     *
     * @param stream       the content, from the beginning
     * @param startOrdinal how many records to discard
     * @return an iterator already positioned past {@code startOrdinal} records
     */
    default Iterator<ParsedRecord<R>> parseFromOrdinal(InputStream stream, long startOrdinal) {
        Iterator<ParsedRecord<R>> records = parse(stream, 0, 0);
        for (long skipped = 0; skipped < startOrdinal && records.hasNext(); skipped++) {
            records.next();
        }
        return records;
    }
}
