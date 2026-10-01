package ru.ludwigandreas.messaging.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import ru.ludwigandreas.messaging.api.MessageHeaders;

/**
 * Everything a deployment may say about how this platform consumes.
 *
 * <p>Two levels, and no more: {@code getDefaults()} is what every consumer gets, and
 * {@code getConsumers()} is a per-consumer override keyed by the name the consumer's container factory
 * was built under. A third level - per topic - was considered and left out: a consumer already owns its
 * topics, and a setting that could differ between two topics of one listener would have to be resolved
 * per record rather than per container, which is not something a container factory can express.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = MessagingProperties.PREFIX)
public class MessagingProperties {

    /** The configuration prefix, named here so the conditionals and the messages cannot disagree. */
    public static final String PREFIX = "ludwig.messaging";

    /**
     * Whether this module wires anything.
     *
     * <p>On by default and gated on {@code KafkaListener} being present besides, which is the arrangement
     * all three wirings this module replaces already used: a deployment with no broker resolves none of
     * this and is unaffected.
     */
    private boolean enabled = true;

    /**
     * The correlation header's name, which must match the observability module's.
     *
     * <p>Defaulted to the same value rather than read from {@code ObservabilityProperties}, because this
     * module does not depend on that one - see the dependency direction note in the POM. A deployment that
     * changes {@code ludwig.observability.correlation.kafka.header-name} changes this too, and the two
     * defaults being the same constant is what makes that a rare need rather than a trap.
     */
    @NotBlank
    private String correlationHeader = MessageHeaders.CORRELATION_ID;

    /** Whether this module's own counters and gauges are bound. */
    @NotNull
    @Valid
    private Metrics metrics = new Metrics();

    /** How the consumer-side dedup filter is attached. */
    @NotNull
    @Valid
    private Dedup dedup = new Dedup();

    /** What every consumer gets unless it overrides it. */
    @NotNull
    @Valid
    private ConsumerSettings defaults = new ConsumerSettings();

    /**
     * Per-consumer overrides, keyed by the name its container factory was built under.
     *
     * <p>A {@code LinkedHashMap} so that a validation failure names consumers in the order they were
     * configured, which is the order the person reading the message wrote them in.
     */
    // No @Valid on the map's value type. A TYPE_USE constraint inside a generic parameter is a cascade
    // instruction that Boot's relaxed binder cannot express in its metadata, and the binding fails before
    // any validation runs. Nothing is lost: every constraint that matters on a consumer's block is a range
    // check that ResolvedConsumerSettings' constructor makes at resolution time, which is the moment the
    // value is used and therefore the moment worth failing at.
    @NotNull
    private Map<String, ConsumerSettings> consumers = new LinkedHashMap<>();

    /**
     * One consumer's settings, with the per-consumer block, the defaults and the platform constants
     * coalesced in that order.
     *
     * @param name the consumer's name
     * @return the resolved settings; never null, and never partially filled
     */
    public ResolvedConsumerSettings resolve(String name) {
        ConsumerSettings own = consumers.get(name);
        return new ResolvedConsumerSettings(
                pick(own, ConsumerSettings::getAckMode, ResolvedConsumerSettings.DEFAULT_ACK_MODE),
                pick(own, ConsumerSettings::getConcurrency, null),
                pick(own, ConsumerSettings::getOrdered, ResolvedConsumerSettings.DEFAULT_ORDERED),
                pick(own, ConsumerSettings::getDedup, ResolvedConsumerSettings.DEFAULT_DEDUP),
                pick(own, ConsumerSettings::getSilenceThreshold, null),
                pick(own, settings -> settings.getRetry().getInitialBackoff(),
                        ResolvedConsumerSettings.DEFAULT_INITIAL_BACKOFF),
                pick(own, settings -> settings.getRetry().getMultiplier(),
                        ResolvedConsumerSettings.DEFAULT_MULTIPLIER),
                pick(own, settings -> settings.getRetry().getMaxBackoff(),
                        ResolvedConsumerSettings.DEFAULT_MAX_BACKOFF),
                pick(own, settings -> settings.getRetry().getMaxAttempts(),
                        ResolvedConsumerSettings.DEFAULT_MAX_ATTEMPTS),
                pick(own, settings -> settings.getRetry().getNonBlocking(),
                        ResolvedConsumerSettings.DEFAULT_NON_BLOCKING),
                pick(own, settings -> settings.getDeadLetter().getEnabled(),
                        ResolvedConsumerSettings.DEFAULT_DEAD_LETTER_ENABLED),
                pick(own, settings -> settings.getDeadLetter().getSuffix(), null),
                pick(own, settings -> settings.getAcceptedVersions().getMin(),
                        ResolvedConsumerSettings.DEFAULT_MIN_EVENT_VERSION),
                pick(own, settings -> settings.getAcceptedVersions().getMax(),
                        ResolvedConsumerSettings.DEFAULT_MAX_EVENT_VERSION));
    }

    /**
     * The first non-null of the per-consumer value, the default value and the platform constant.
     *
     * <p>A generic helper rather than fourteen three-line {@code if} chains, and the reason it takes a
     * getter rather than two values is the nested blocks: {@code retry} and {@code dead-letter} are
     * objects that are never null themselves, so reaching a nullable leaf means applying the same
     * navigation to both levels.
     */
    private <T> T pick(ConsumerSettings own, Function<ConsumerSettings, T> getter, T fallback) {
        T value = own == null ? null : getter.apply(own);
        if (value != null) {
            return value;
        }
        value = getter.apply(defaults);
        return value != null ? value : fallback;
    }

    /** Whether this module's own counters and gauges are bound. */
    @Getter
    @Setter
    public static class Metrics {

        /**
         * On by default when Micrometer is on the classpath.
         *
         * <p>Including the {@code MicrometerConsumerListener} that binds the Kafka client's own metrics,
         * consumer lag among them. Nothing in this platform bound that before, which is why a platform
         * with an observability starter had no consumer lag metric at all.
         */
        private boolean enabled = true;
    }

    /** How the consumer-side dedup filter from {@code idempotency-spring-boot-starter} is attached. */
    @Getter
    @Setter
    public static class Dedup {

        /**
         * Whether a {@code RecordFilterStrategy} found in the context is attached to this module's
         * container factories.
         *
         * <p>On by default, which is this module's entire job on dedup: the inbox already exists, and what
         * was missing was that adopting it took a line in every service's own factory. A deployment that
         * switches {@code ludwig.idempotency.kafka.enabled} on now gets dedup on every consumer built here.
         * Nothing is attached when no such bean exists, so this being on by default costs a deployment
         * without the idempotency starter nothing.
         *
         * <p>A per-consumer opt-out is {@link ConsumerSettings#getDedup()}, because the decision is
         * per-listener: a projection that converges on a value is already correct on a replay.
         */
        private boolean enabled = true;

        /**
         * Whether the container is given the application's transaction manager so a claim commits with the
         * listener's work.
         *
         * <p>Null means "decide from the idempotency module's claim mode", which is what a deployment
         * should almost always want and is why the default is not simply false: a
         * {@code ClaimMode.TRANSACTIONAL} claim on a container with no transaction manager commits outside
         * the listener's transaction, so a key stays reserved for work that rolled back - and the
         * redelivery Kafka will certainly perform is then discarded as a duplicate. The message is never
         * delivered and nothing says so. {@code MessagingDedupAutoConfiguration} refuses that combination
         * at startup when this is explicitly {@code false} while the claim mode is transactional.
         */
        private Boolean containerTransaction;
    }
}
