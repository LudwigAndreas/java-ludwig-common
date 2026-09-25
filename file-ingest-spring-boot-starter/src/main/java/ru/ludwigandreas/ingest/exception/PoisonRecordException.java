package ru.ludwigandreas.ingest.exception;

/**
 * One record is larger than a whole batch is allowed to be.
 *
 * <p>Not thrown out of the run - it is caught by the engine and turned into a quarantine row, which is
 * the point. A record bigger than {@code batch.max-bytes} cannot be accumulated into any batch, so the
 * alternatives are to grow the batch until the heap runs out, or to name the record and set it aside.
 * The first is an {@code OutOfMemoryError} with no indication of which of four million rows caused it,
 * on a day a partner sent one row with a 200 MB free-text column.
 *
 * <p>Exists as a type so the quarantine row records <em>why</em>, and so the count of these is
 * distinguishable from parse failures in the audit trail. They mean different things: a parse failure
 * is a malformed file, an oversized record is a correctly formed file the batch bound cannot carry.
 */
public class PoisonRecordException extends IngestException {

    private static final long serialVersionUID = 1L;

    /**
     * A record too large for any batch.
     *
     * @param ordinal   the record's index in the object
     * @param sizeBytes how large it was
     * @param maxBytes  the task's batch byte bound
     */
    public PoisonRecordException(long ordinal, long sizeBytes, long maxBytes) {
        super("Record " + ordinal + " is " + sizeBytes + " bytes, larger than the task's"
                + " batch.max-bytes of " + maxBytes + ", so no batch can hold it. Quarantined rather"
                + " than grown into, because growing is an OutOfMemoryError that names nothing.");
    }
}
