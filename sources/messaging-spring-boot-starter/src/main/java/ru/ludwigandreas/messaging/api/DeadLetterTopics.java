package ru.ludwigandreas.messaging.api;

/**
 * The platform's one dead-letter topic naming convention.
 *
 * <p>{@code .dlt} appended to the source topic, which is what {@code NotificationMessagingConfig}
 * used - the only one of the three consumers that had a dead-letter topic at all. Stated here because a
 * suffix that each module spells for itself is a suffix that will eventually be spelled two ways, and
 * the symptom is an operator alerting on {@code orders.dlt} while records pile up in
 * {@code orders-dlq}.
 *
 * <p>A Checkstyle rule, {@code SecondDeadLetterSuffix}, fails the build on a second declaration of a
 * suffix constant. It is a Checkstyle rule rather than an ArchUnit one for the same reason
 * {@code SecondRedactionMask} is: ArchUnit reads bytecode, where a {@code static final String}'s value
 * is a constant-pool entry {@code JavaField} does not expose, so "no field whose value is {@code .dlt}"
 * is not expressible there. What ArchUnit does check is the structural half - that nothing outside this
 * module declares its own {@code DeadLetterPublishingRecoverer}.
 */
public final class DeadLetterTopics {

    /** Appended to a source topic's name. The one spelling; see the class comment. */
    // SUPPRESS CHECKSTYLE ID SecondDeadLetterSuffix - this is the first one, and the reason the rule
    // exists. Every other declaration of a dead-letter suffix in the platform is the second one.
    public static final String DEFAULT_SUFFIX = ".dlt";

    /**
     * The partition a dead-letter send targets.
     *
     * <p>{@code -1} lets the broker choose. The comment this carried in
     * {@code NotificationMessagingConfig} is the reason and is worth keeping verbatim: preserving the
     * source partition would require the dead-letter topic to have at least as many partitions, which
     * nothing enforces - and a send to a partition that does not exist fails silently.
     *
     * <p>"Fails silently" is the operative half. The record is gone, the container has moved on, the
     * offset is committed, and the only trace is a producer-side error that nothing is reading. A
     * broker-chosen partition loses the ordering of dead-lettered records relative to each other, which
     * is a property nobody needs from a topic whose records are read by a human.
     */
    public static final int BROKER_CHOOSES_PARTITION = -1;

    /**
     * The dead-letter topic for a source topic.
     *
     * @param topic  the source topic
     * @param suffix the suffix, normally {@link #DEFAULT_SUFFIX}
     * @return the dead-letter topic's name
     */
    public static String forTopic(String topic, String suffix) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("A dead-letter topic needs a source topic");
        }
        return topic + (suffix == null || suffix.isBlank() ? DEFAULT_SUFFIX : suffix);
    }

    private DeadLetterTopics() {
    }
}
