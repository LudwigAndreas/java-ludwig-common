package ru.ludwigandreas.ingest.api;

/**
 * One record could not be written, and the rest of the batch still can be.
 *
 * <p>Its own type so that an applier can distinguish the two cases it is in, which have opposite
 * consequences: a record that violates a check constraint is a quarantine row and the run continues,
 * while a staging table that is not there fails the run. Without a distinguished type the engine would
 * have to treat every exception the same way, and whichever way it chose would be wrong half the time
 * - quarantining everything would import nothing and report success, failing on everything would make
 * one malformed row cost a four-million-row run.
 *
 * <p>Deliberately <em>not</em> a {@code LocalizedException}: there is no caller waiting on a response,
 * and the message goes into a quarantine row that an operator reads, not into a response body.
 */
public class RecordApplyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Object record;

    /**
     * A record that could not be written.
     *
     * @param record  the record, so the engine can match it back to its offset and raw text
     * @param message what was wrong with it
     */
    public RecordApplyException(Object record, String message) {
        super(message);
        this.record = record;
    }

    /**
     * A record that could not be written, with a cause.
     *
     * @param record  the record
     * @param message what was wrong with it
     * @param cause   what the database or the applier threw
     */
    public RecordApplyException(Object record, String message, Throwable cause) {
        super(message, cause);
        this.record = record;
    }

    /**
     * The record that failed.
     *
     * @return the record, which the engine uses to find the offset and raw text for the quarantine row
     */
    public Object record() {
        return record;
    }
}
