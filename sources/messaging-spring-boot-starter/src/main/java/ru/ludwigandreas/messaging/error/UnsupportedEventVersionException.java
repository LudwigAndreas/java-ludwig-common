package ru.ludwigandreas.messaging.error;

/**
 * The record's {@code ludwig-event-version} is outside the range this consumer accepts.
 *
 * <p>Raised from inside the deserializer, before the payload has been parsed. That timing is the entire
 * point and it is why this is not a check in the listener: by the time a listener runs, the bytes have
 * already been mapped onto the consumer's current type, and Jackson's default behaviour for a field
 * that moved, split or changed meaning is not to complain - it is to leave the new field at its default
 * and carry on. A consumer that "handles" a version it does not understand writes a row with a field
 * silently missing, and nothing anywhere records that it happened.
 *
 * <p>Wrapped by {@code ErrorHandlingDeserializer} into a {@code DeserializationException}, which the
 * shared error handler has registered as non-retryable - so the record reaches the dead-letter topic on
 * its first attempt rather than after four, which is correct: a version does not change on redelivery.
 *
 * <p>The payload is never in the message. This platform's topics carry a person's settings and
 * directory data, and this exception's message reaches a log and a dead-letter header.
 */
public class UnsupportedEventVersionException extends MessagingException {

    private static final long serialVersionUID = 1L;

    private final String topic;
    private final int eventVersion;
    private final int minAccepted;
    private final int maxAccepted;

    /**
     * Creates the exception.
     *
     * @param topic        the topic the record came from
     * @param eventVersion the version the record declared
     * @param minAccepted  the lowest version this consumer accepts
     * @param maxAccepted  the highest version this consumer accepts
     */
    public UnsupportedEventVersionException(String topic, int eventVersion, int minAccepted, int maxAccepted) {
        super(MessagingProblemCodes.UNSUPPORTED_EVENT_VERSION,
                "Record on " + topic + " declares event version " + eventVersion
                        + ", which is outside the accepted range " + minAccepted + ".." + maxAccepted);
        this.topic = topic;
        this.eventVersion = eventVersion;
        this.minAccepted = minAccepted;
        this.maxAccepted = maxAccepted;
    }

    public String getTopic() {
        return topic;
    }

    public int getEventVersion() {
        return eventVersion;
    }

    public int getMinAccepted() {
        return minAccepted;
    }

    public int getMaxAccepted() {
        return maxAccepted;
    }
}
