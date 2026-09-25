package ru.ludwigandreas.ingest.engine;

import java.util.ArrayList;
import java.util.List;
import ru.ludwigandreas.ingest.api.ParsedRecord;

/**
 * Collects records until a batch is full, where "full" is two numbers rather than one.
 *
 * <h2>Memory is bounded in bytes, not in records</h2>
 *
 * <p>A record-count bound is the obvious one and is the wrong one on its own. Five thousand records
 * is a comfortable batch right up until a partner sends one row with a 200 MB free-text column, at
 * which point five thousand records is a gigabyte and the process dies with an
 * {@code OutOfMemoryError} that names none of the four million rows in the file. The heap is consumed
 * in bytes, so the bound that actually protects it has to be in bytes.
 *
 * <p>Both bounds are enforced and the batch flushes at whichever is reached first. The record count
 * still matters - it bounds the size of the statement the staging writer builds, which is a different
 * resource from the heap - so this is two bounds on two things rather than one bound expressed twice.
 *
 * <h2>A record larger than the whole bound is a poison record</h2>
 *
 * <p>It cannot go in any batch, so the choice is to grow the batch until the heap runs out, or to
 * name the record and set it aside. {@link #wouldOverflowAlone} is how the engine asks, before
 * accepting anything, so the oversized record becomes a quarantine row carrying its offset rather
 * than an out-of-memory error carrying nothing.
 *
 * <p>Not thread-safe, and deliberately so: it belongs to one run on one thread, and a shared
 * accumulator would be a batch two threads could flush concurrently, which is a checkpoint written
 * twice.
 */
public class BatchAccumulator<R> {

    private final int maxRecords;
    private final long maxBytes;

    private final List<R> values = new ArrayList<>();
    private long bytes;
    private long endOffset;
    private long lastOrdinal = -1;

    /**
     * Creates an accumulator.
     *
     * @param maxRecords records before a flush
     * @param maxBytes   bytes before a flush, whichever comes first
     */
    public BatchAccumulator(int maxRecords, long maxBytes) {
        if (maxRecords < 1) {
            throw new IllegalArgumentException("A batch needs room for at least one record");
        }
        if (maxBytes < 1) {
            throw new IllegalArgumentException("A batch needs a positive byte bound");
        }
        this.maxRecords = maxRecords;
        this.maxBytes = maxBytes;
    }

    /**
     * Whether this record is too large for any batch, and so is a poison record.
     *
     * <p>Asked before {@link #add}, and independently of what is already accumulated: a record that
     * would not fit in an <em>empty</em> batch will never fit, and flushing first would only defer the
     * failure by one batch.
     *
     * @param record the record
     * @return {@code true} if it is larger than the byte bound on its own
     */
    public boolean wouldOverflowAlone(ParsedRecord<R> record) {
        return record.sizeBytes() > maxBytes;
    }

    /**
     * Whether adding this record would exceed a bound, so the batch should be flushed first.
     *
     * <p>Checked before adding rather than after, so the batch never momentarily holds more than its
     * bound. Checking afterwards would let the peak exceed the limit by one record - which for the
     * record this bound exists to protect against is the entire problem.
     *
     * @param record the record about to be added
     * @return {@code true} if the batch should be flushed before accepting it
     */
    public boolean shouldFlushBefore(ParsedRecord<R> record) {
        if (values.isEmpty()) {
            return false;
        }
        return values.size() >= maxRecords || bytes + record.sizeBytes() > maxBytes;
    }

    /**
     * Adds a record.
     *
     * @param record the record, which must have parsed
     */
    public void add(ParsedRecord<R> record) {
        values.add(record.value());
        bytes += record.sizeBytes();
        endOffset = record.endOffset();
        lastOrdinal = record.ordinal();
    }

    /**
     * Whether there is anything to flush.
     *
     * @return {@code true} if the batch holds at least one record
     */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * How many records are held.
     *
     * @return the count
     */
    public int size() {
        return values.size();
    }

    /**
     * How many bytes are held.
     *
     * @return the byte count
     */
    public long bytes() {
        return bytes;
    }

    /**
     * The byte offset of the first byte after the last record in this batch.
     *
     * <p>What a {@code Checkpoint.ByteOffset} advances to when the batch commits. Taken from the
     * record rather than accumulated from sizes, because the parser knows where the record ended and
     * the sum of record sizes omits any delimiter between them.
     *
     * @return the end offset
     */
    public long endOffset() {
        return endOffset;
    }

    /**
     * The ordinal of the last record in this batch.
     *
     * @return the ordinal, or {@code -1} for an empty batch
     */
    public long lastOrdinal() {
        return lastOrdinal;
    }

    /**
     * The records, in source order.
     *
     * @return an immutable view of the batch
     */
    public List<R> values() {
        return List.copyOf(values);
    }

    /**
     * Empties the accumulator after a successful flush.
     *
     * <p>Called by the engine <em>after</em> the transaction commits, never before. Clearing first and
     * committing afterwards would lose the batch if the commit failed, and the checkpoint would not
     * have moved - so the records would be neither written nor re-read.
     */
    public void clear() {
        values.clear();
        bytes = 0;
    }
}
