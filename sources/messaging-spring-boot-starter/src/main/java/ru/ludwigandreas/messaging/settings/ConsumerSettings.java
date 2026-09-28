package ru.ludwigandreas.messaging.settings;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.kafka.listener.ContainerProperties;

/**
 * What one consumer, or the platform default, may say about itself.
 *
 * <p>Every field is nullable, and that is the whole design of this class. A per-consumer override has to
 * be able to say "everything as the default, but four attempts instead of six", and a primitive field
 * cannot express the difference between "not set" and "set to zero" - so a per-consumer block with a
 * primitive {@code maxAttempts} would silently reset it to zero for every consumer that overrode anything
 * else. {@link MessagingProperties#resolve(String)} coalesces per-consumer, then default, then the
 * platform constant, and {@link ResolvedConsumerSettings} is what the wiring sees.
 *
 * <p>The platform constants themselves are on {@link ResolvedConsumerSettings}, named and documented, so
 * that "what happens if I configure nothing" has one answer in one place rather than being spread across
 * field initialisers here.
 */
@Getter
@Setter
public class ConsumerSettings {

    /**
     * When the container commits offsets.
     *
     * <p>{@code RECORD} by default. The three consumers this module replaces used {@code RECORD},
     * {@code MANUAL} and - by inheriting Boot's default - {@code BATCH}, which is three different answers
     * to "what happens to the other records in this poll when one of them fails" for one semantic. With
     * at-least-once delivery and dedup available in front, per-record acknowledgement gives the smallest
     * redelivery window and the simplest reasoning: a record is committed when its listener returned, and
     * nothing else in the poll is affected by its failure.
     *
     * <p>{@code MANUAL} remains available for a consumer that genuinely batches, and taking it requires
     * setting this property - so nobody inherits it by accident, which is how user-settings ended up with
     * manual acknowledgement and no error handler.
     */
    private ContainerProperties.AckMode ackMode;

    /** Listener threads. Null leaves the factory's own default, which is one. */
    private Integer concurrency;

    /**
     * Whether same-key ordering is a promise this consumer relies on.
     *
     * <p>True by default, because the producer side publishes an {@code orderingKey} and that is a promise
     * somebody made. Declaring {@code false} is how a consumer says it does not depend on it, and it is a
     * precondition for {@link Retry#nonBlocking} - see
     * {@link ru.ludwigandreas.messaging.error.OrderingConflictsWithRetryTopicsException}.
     */
    private Boolean ordered;

    /**
     * Whether the consumer-side dedup filter is attached to this consumer's containers when one is
     * present in the context.
     *
     * <p>True by default, which is what makes dedup a matter of configuration rather than of remembering:
     * a deployment that switches {@code ludwig.idempotency.kafka.enabled} on gets it on every consumer
     * this module builds. Set to false for a listener that must see every delivery - a projection that
     * converges on a value is already correct on a replay, and putting a claim in front of it adds a
     * table, a write and a failure mode for a guarantee it already had.
     */
    private Boolean dedup;

    /**
     * How long nothing may arrive on this consumer's topics before the silence gauge fires.
     *
     * <p>Null by default, and deliberately so: a threshold is a statement about a topic's traffic that only
     * the team owning it can make, and a wrong one is worse than none - it either cries wolf every quiet
     * evening or never fires at all. See
     * {@link ru.ludwigandreas.messaging.metrics.ConsumerActivityMonitor} for why this is the signal that
     * matters and why lag cannot replace it.
     */
    private Duration silenceThreshold;

    /**
     * The topics this consumer reads, when they have to be stated in configuration rather than inferred.
     *
     * <p>Normally empty, because a consumer's topics are on its {@code @KafkaListener} and the builder is
     * told them directly. It is needed for exactly one thing: a {@code RetryTopicConfiguration} is declared
     * independently of any listener, so a consumer opting into non-blocking retries has to name the topics
     * the retry and dead-letter topics are derived from. {@code MessagingConfigurationValidator} refuses
     * the opt-in without them, because the alternative is a retry-topic configuration that silently
     * applies to no topic at all - which looks exactly like retries working.
     */
    private List<String> topics = new ArrayList<>();

    /** Retry behaviour. Never null as a block; its own fields are. */
    private Retry retry = new Retry();

    /** Dead-letter behaviour. Never null as a block; its own fields are. */
    private DeadLetter deadLetter = new DeadLetter();

    /** Accepted payload schema versions. Never null as a block; its own fields are. */
    private AcceptedVersions acceptedVersions = new AcceptedVersions();

    /**
     * How a failing record is retried.
     *
     * <p>The defaults are {@code NotificationMessagingConfig}'s, which was the only one of the three
     * wirings that had thought about it: one second, tripling, capped at thirty, four attempts including
     * the first.
     */
    @Getter
    @Setter
    public static class Retry {

        /** The first gap. */
        private Duration initialBackoff;

        /** Each gap is this many times the last. */
        private Double multiplier;

        /** No gap exceeds this, however many attempts. */
        private Duration maxBackoff;

        /** Attempts before the record is dead-lettered, including the first. */
        private Integer maxAttempts;

        /**
         * Whether retries happen on retry topics instead of in the container.
         *
         * <p>False by default, and this is the one default in this class that is a correctness decision
         * rather than a sensible starting point.
         *
         * <p>A blocking {@code DefaultErrorHandler} pauses the partition while it retries - head-of-line
         * blocking - and therefore preserves order. Non-blocking retry topics keep throughput up and
         * destroy ordering: the retried record is re-applied after records that were produced later than
         * it. The producer side deliberately publishes an {@code orderingKey}, which is a promise that
         * same-key events are applied in order, and switching a topic to non-blocking retries breaks that
         * promise silently. The symptom is a corrected value that reverts hours later, which is the hardest
         * class of bug to attribute.
         *
         * <p>So it is per-topic opt-in, and the opt-in is refused at startup for any consumer that has not
         * also declared {@code ordered: false}. A warning log would be read by nobody, once.
         */
        private Boolean nonBlocking;
    }

    /** Where a record that could not be processed goes. */
    @Getter
    @Setter
    public static class DeadLetter {

        /**
         * Whether a dead-letter topic is used at all.
         *
         * <p>True by default. Off means the error handler logs and moves on once the attempts are spent,
         * which is Boot's default behaviour and is the identity-projection defect this module exists to
         * remove - so turning it off is a deliberate, named decision rather than something a deployment
         * can arrive at by omission.
         */
        private Boolean enabled;

        /**
         * Appended to the source topic's name.
         *
         * <p>{@code .dlt} by default, from {@link ru.ludwigandreas.messaging.api.DeadLetterTopics}. A
         * consumer that wants a different destination changes this; a consumer that wants a
         * <em>version-specific</em> destination for a rejected event version also changes this, which is
         * why {@code VersionGatingDeserializer} rejects rather than routing.
         */
        private String suffix;
    }

    /**
     * The payload schema versions this consumer will deserialize.
     *
     * <p>Unbounded by default, which preserves what every consumer did before this module: accept
     * anything. That is the wrong default in the abstract and the right one here, because a bound this
     * module invented would dead-letter live traffic on the deploy that introduced it. A consumer declares
     * its bound when it knows what it is, and gets a refusal at the deserializer instead of a silently
     * mismapped payload - see {@code VersionGatingDeserializer}.
     */
    @Getter
    @Setter
    public static class AcceptedVersions {

        /** The lowest accepted version, inclusive. */
        private Integer min;

        /** The highest accepted version, inclusive. */
        private Integer max;
    }
}
