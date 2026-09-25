package ru.ludwigandreas.ingest.api;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * What one run did, in the form the receipt object and the completion event both carry.
 *
 * <p>One type for both on purpose. A receipt written into the bucket and an {@code ingest.completed}
 * event published through the outbox are the same statement made to two audiences, and letting them
 * drift means an operator reading the receipt and a downstream service reading the event disagree
 * about how many records arrived - which is exactly the kind of disagreement that takes a day to
 * resolve because neither side is obviously wrong.
 *
 * @param runId             the run
 * @param task              the task name
 * @param sourceUri         the object that was read, in canonical form
 * @param contentIdentity   the etag or version the run was keyed on, which is what makes a re-offered
 *                          file recognisable as the same one
 * @param bytesRead         uncompressed bytes the parser consumed
 * @param recordsRead       records the parser produced, good and bad
 * @param recordsApplied    records the applier wrote into staging
 * @param recordsQuarantined records that failed to parse or to apply
 * @param recordsSkipped    records the applier itself collapsed, for example a duplicate key within
 *                          the file
 * @param startedAt         when the run claimed the object
 * @param finishedAt        when it reached {@link IngestRunStatus#COMPLETED}
 */
public record IngestRunSummary(UUID runId, String task, String sourceUri, String contentIdentity,
                               long bytesRead, long recordsRead, long recordsApplied,
                               long recordsQuarantined, long recordsSkipped,
                               Instant startedAt, Instant finishedAt) {

    /** The aggregate type an outbox event carries. */
    public static final String AGGREGATE_TYPE = "file-ingest-run";

    /** The event type an outbox event carries. */
    public static final String EVENT_TYPE = "ingest.completed";

    /**
     * How long the run took.
     *
     * @return the wall-clock duration, or {@link Duration#ZERO} if it has not finished
     */
    public Duration duration() {
        return finishedAt == null || startedAt == null ? Duration.ZERO
                : Duration.between(startedAt, finishedAt);
    }

    /**
     * Whether the counts add up.
     *
     * <p>The arithmetic the run is not allowed to complete without, kept on the summary as well as in
     * the engine so that a receipt or an event can be checked by whoever receives it. A consumer that
     * verifies this is verifying the producer's claim rather than trusting it, and the cost of doing
     * so is one addition.
     *
     * @return {@code true} if read equals applied plus quarantined plus skipped
     */
    public boolean balances() {
        return recordsRead == recordsApplied + recordsQuarantined + recordsSkipped;
    }
}
