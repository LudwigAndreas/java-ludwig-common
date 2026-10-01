package ru.ludwigandreas.messaging.settings;

import java.time.Duration;
import org.springframework.kafka.listener.ContainerProperties;
import ru.ludwigandreas.messaging.api.DeadLetterTopics;

/**
 * One consumer's settings with every question answered, which is what the wiring reads.
 *
 * <p>The platform's defaults live here as named constants rather than as field initialisers on
 * {@link ConsumerSettings}, so that "what does a consumer that configures nothing get" has one answer in
 * one place - and so that the answer is greppable from the class that documents why each value is what it
 * is.
 *
 * <p>The retry values are {@code NotificationMessagingConfig}'s. That is deliberate rather than
 * arbitrary: it was the only one of the three wirings this module replaces that had reasoned about its
 * error handling, so adopting its numbers means the reference consumer's effective behaviour does not
 * change when it becomes a caller.
 *
 * @param ackMode          when offsets are committed
 * @param concurrency      listener threads, or {@code null} to leave the factory's default
 * @param ordered          whether same-key ordering is relied on
 * @param dedup            whether the dedup filter is attached when one is available
 * @param silenceThreshold how long an absence is tolerable, or {@code null} for no silence signal
 * @param initialBackoff   the first retry gap
 * @param multiplier       the growth factor between gaps
 * @param maxBackoff       the cap on a gap
 * @param maxAttempts      attempts before dead-lettering, including the first
 * @param nonBlocking      whether retries go to retry topics instead of blocking the partition
 * @param deadLetterEnabled whether a dead-letter topic is used
 * @param deadLetterSuffix  appended to the source topic
 * @param minEventVersion   the lowest payload schema version accepted
 * @param maxEventVersion   the highest payload schema version accepted
 */
public record ResolvedConsumerSettings(
        ContainerProperties.AckMode ackMode,
        Integer concurrency,
        boolean ordered,
        boolean dedup,
        Duration silenceThreshold,
        Duration initialBackoff,
        double multiplier,
        Duration maxBackoff,
        int maxAttempts,
        boolean nonBlocking,
        boolean deadLetterEnabled,
        String deadLetterSuffix,
        int minEventVersion,
        int maxEventVersion) {

    /** Per-record acknowledgement - see {@code ConsumerSettings.getAckMode()}. */
    public static final ContainerProperties.AckMode DEFAULT_ACK_MODE = ContainerProperties.AckMode.RECORD;

    /** The producer promises same-key order, so a consumer is assumed to rely on it until it says not. */
    public static final boolean DEFAULT_ORDERED = true;

    /** Dedup is on wherever a filter strategy exists, so a deployment enables it in one place. */
    public static final boolean DEFAULT_DEDUP = true;

    /** The first retry gap. */
    public static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofSeconds(1);

    /** Gaps triple. */
    public static final double DEFAULT_MULTIPLIER = 3.0;

    /** No gap exceeds thirty seconds, so a partition is never paused for minutes by one bad record. */
    public static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(30);

    /** Four attempts including the first: 1s, 3s, 9s, then out. */
    public static final int DEFAULT_MAX_ATTEMPTS = 4;

    /** Blocking retries, for the ordering reason on {@code ConsumerSettings.Retry.getNonBlocking()}. */
    public static final boolean DEFAULT_NON_BLOCKING = false;

    /** A dead-letter topic exists unless a deployment names a reason it should not. */
    public static final boolean DEFAULT_DEAD_LETTER_ENABLED = true;

    /** The lowest version accepted when a consumer declares no bound. */
    public static final int DEFAULT_MIN_EVENT_VERSION = 1;

    /** The highest version accepted when a consumer declares no bound - see {@code AcceptedVersions}. */
    public static final int DEFAULT_MAX_EVENT_VERSION = Integer.MAX_VALUE;

    /**
     * Validates the resolved combination.
     *
     * <p>Only the things that cannot be expressed as a per-field default. The ordering conflict is checked
     * separately, by {@code MessagingConfigurationValidator}, because the message has to name the consumer
     * and a record's constructor does not know its own name.
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - a record's canonical constructor, assembled once by
    // MessagingProperties.resolve and never written out by hand.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public ResolvedConsumerSettings {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException(
                    "retry.max-attempts counts the first delivery, so it is at least 1: " + maxAttempts);
        }
        if (minEventVersion > maxEventVersion) {
            throw new IllegalArgumentException("accepted-versions runs low to high: "
                    + minEventVersion + ".." + maxEventVersion);
        }
        deadLetterSuffix = deadLetterSuffix == null || deadLetterSuffix.isBlank()
                ? DeadLetterTopics.DEFAULT_SUFFIX
                : deadLetterSuffix;
    }

    /**
     * Whether this consumer bounds the payload versions it accepts.
     *
     * @return {@code false} when it accepts anything, in which case no version gate is installed at all
     */
    public boolean gatesEventVersion() {
        return minEventVersion > DEFAULT_MIN_EVENT_VERSION || maxEventVersion < DEFAULT_MAX_EVENT_VERSION;
    }
}
