package ru.ludwigandreas.ingest.api;

/**
 * How far into an object a run has got, in the only terms the object's format can express.
 *
 * <h2>Why this is sealed over two shapes rather than one long</h2>
 *
 * <p>The obvious model is a byte offset, and for a line-delimited format it is the right one: a
 * resume is a ranged GET starting at the offset, which costs nothing and reads nothing twice. Build
 * only that, and the first XML feed forces the loop to be rewritten rather than extended, because a
 * byte offset into an XML document is not a record boundary and seeking to one lands in the middle of
 * an element. The same is true of Parquet, of fixed-block formats, and of anything with a header that
 * has to be re-read.
 *
 * <p>So there are two shapes from the start, and the choice belongs to the parser rather than to the
 * configuration: {@link RecordParser#checkpointKind()} declares which one it can produce, and the
 * engine resumes accordingly. Adding a third shape means adding a variant here and a case to the
 * engine's switch, which the compiler will point at - that is what {@code sealed} buys.
 *
 * <p>Both shapes are equally <em>correct</em>. They differ only in what a resume costs, and saying so
 * is important: an {@link RecordOrdinal} checkpoint is not a degraded checkpoint, it is a checkpoint
 * whose resume re-reads and discards rather than seeking. A run that resumes correctly but slowly is
 * a working run.
 */
public sealed interface Checkpoint permits Checkpoint.ByteOffset, Checkpoint.RecordOrdinal {

    /**
     * How many records have been committed so far, whichever shape this is.
     *
     * <p>Carried by both variants because the balance check needs it and because it is what an
     * operator reads off the actuator endpoint to know whether a stuck run is stuck or slow. A byte
     * offset alone answers neither question.
     *
     * @return the number of records the run has committed
     */
    long recordsCommitted();

    /**
     * The value stored in the run row's checkpoint column.
     *
     * @return a byte offset or a record ordinal, depending on the shape
     */
    long position();

    /**
     * Which shape this is, for persistence and for the engine's resume decision.
     *
     * @return the kind
     */
    CheckpointKind kind();

    /**
     * The start of an object nothing has been read from yet.
     *
     * @param kind the shape the parser produces
     * @return a checkpoint at position zero with no records committed
     */
    static Checkpoint start(CheckpointKind kind) {
        return kind == CheckpointKind.BYTE_OFFSET ? new ByteOffset(0, 0) : new RecordOrdinal(0);
    }

    /**
     * Rebuilds a checkpoint from what was stored on the run row.
     *
     * @param kind             the shape, as the parser declares it
     * @param position         the stored position
     * @param recordsCommitted the stored record count
     * @return the checkpoint
     */
    static Checkpoint of(CheckpointKind kind, long position, long recordsCommitted) {
        return kind == CheckpointKind.BYTE_OFFSET
                ? new ByteOffset(position, recordsCommitted)
                : new RecordOrdinal(recordsCommitted);
    }

    /**
     * A position in bytes, for a format where a byte offset is a record boundary.
     *
     * <p>Line-delimited formats - CSV, NDJSON, anything one-record-per-line. Resuming is a ranged GET
     * from {@code offset} and costs one request; nothing already consumed is transferred again. This
     * is the shape that makes a 1 GB daily drop restartable at no cost, and the reason
     * {@code ObjectStore} has a ranged read at all.
     *
     * <p>{@code offset} is the offset of the <em>first byte not yet committed</em>, so it is also
     * exactly the number of bytes committed. Defining it as the end of the last committed record
     * rather than the start of it is what makes {@code open(uri, ByteRange.from(offset))} correct with
     * no adjustment at the call site - an off-by-one here duplicates or drops one record per resume,
     * which is the single most likely way this module could lose data.
     *
     * @param offset           the first byte not yet committed
     * @param recordsCommitted how many records those bytes contained
     */
    record ByteOffset(long offset, long recordsCommitted) implements Checkpoint {

        /**
         * Validates the position.
         *
         * @throws IllegalArgumentException if either number is negative
         */
        public ByteOffset {
            if (offset < 0 || recordsCommitted < 0) {
                throw new IllegalArgumentException(
                        "A byte-offset checkpoint cannot be negative: " + offset + "/" + recordsCommitted);
            }
        }

        @Override
        public long position() {
            return offset;
        }

        @Override
        public CheckpointKind kind() {
            return CheckpointKind.BYTE_OFFSET;
        }

        /**
         * The checkpoint after a batch ending at a known byte position.
         *
         * @param endOffset the first byte after the last record in the batch
         * @param records   how many records the batch held
         * @return the advanced checkpoint
         */
        public ByteOffset advancedTo(long endOffset, long records) {
            return new ByteOffset(endOffset, recordsCommitted + records);
        }
    }

    /**
     * A position in records, for a format where a byte offset is not a record boundary.
     *
     * <p>XML, Parquet, fixed-block, or any format with a header the parser must re-read. Resuming
     * means opening the object from the beginning and skipping forward to the ordinal, which
     * re-transfers and re-parses everything already consumed. That is slower - materially so, near the
     * end of a large file - and it is still correct, which is the property that matters. A run that
     * cannot seek is not a run that cannot restart.
     *
     * <p>The ordinal is the count of records already committed, so it is both the position and the
     * number to skip.
     *
     * @param ordinal how many records have been committed, and therefore how many to skip on resume
     */
    record RecordOrdinal(long ordinal) implements Checkpoint {

        /**
         * Validates the position.
         *
         * @throws IllegalArgumentException if the ordinal is negative
         */
        public RecordOrdinal {
            if (ordinal < 0) {
                throw new IllegalArgumentException("A record-ordinal checkpoint cannot be negative: " + ordinal);
            }
        }

        @Override
        public long recordsCommitted() {
            return ordinal;
        }

        @Override
        public long position() {
            return ordinal;
        }

        @Override
        public CheckpointKind kind() {
            return CheckpointKind.RECORD_ORDINAL;
        }

        /**
         * The checkpoint after a batch.
         *
         * @param records how many records the batch held
         * @return the advanced checkpoint
         */
        public RecordOrdinal advancedBy(long records) {
            return new RecordOrdinal(ordinal + records);
        }
    }
}
