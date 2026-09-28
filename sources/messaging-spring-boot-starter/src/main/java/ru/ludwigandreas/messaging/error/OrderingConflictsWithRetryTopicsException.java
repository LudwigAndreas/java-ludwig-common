package ru.ludwigandreas.messaging.error;

/**
 * A consumer asked for non-blocking retry topics while declaring that it cares about ordering.
 *
 * <p>Thrown at startup, which is the whole design decision. The brief this module was written to
 * asked for a real check rather than a warning log, and the reason is what the misconfiguration does:
 * a non-blocking retry republishes the failed record to a retry topic and lets the partition continue,
 * so the record is re-applied <em>after</em> records that were produced later than it. The producer
 * side publishes an {@code orderingKey} precisely as a promise that same-key events are applied in
 * order, and this configuration breaks that promise silently. The symptom is a value that was
 * corrected and then reverts hours later, which is the hardest class of bug to attribute - by the time
 * anyone looks, the retry topic is empty and the logs have rolled.
 *
 * <p>A warning log would be read by nobody, once, on the deploy that introduced it.
 */
public class OrderingConflictsWithRetryTopicsException extends MessagingException {

    private static final long serialVersionUID = 1L;

    private final String consumer;

    /**
     * Creates the exception.
     *
     * @param consumer the name of the consumer configuration that declared both
     */
    public OrderingConflictsWithRetryTopicsException(String consumer) {
        super(MessagingProblemCodes.ORDERING_CONFLICTS_WITH_RETRY_TOPICS,
                "Consumer '" + consumer + "' sets retry.non-blocking=true while ordered=true."
                        + " Non-blocking retries re-apply a failed record after records produced later"
                        + " than it, which breaks the ordering the producer's orderingKey promises."
                        + " Set ludwig.messaging.consumers." + consumer + ".ordered=false to accept that,"
                        + " or leave retries blocking.");
        this.consumer = consumer;
    }

    public String getConsumer() {
        return consumer;
    }
}
