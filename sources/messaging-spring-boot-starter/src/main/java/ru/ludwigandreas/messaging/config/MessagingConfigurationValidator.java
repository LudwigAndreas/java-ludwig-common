package ru.ludwigandreas.messaging.config;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import ru.ludwigandreas.messaging.error.OrderingConflictsWithRetryTopicsException;
import ru.ludwigandreas.messaging.settings.ConsumerSettings;
import ru.ludwigandreas.messaging.settings.DedupTransactionPolicy;
import ru.ludwigandreas.messaging.settings.MessagingProperties;
import ru.ludwigandreas.messaging.settings.ResolvedConsumerSettings;

/**
 * Refuses, at startup, a configuration that cannot mean what it says.
 *
 * <p>Every check here could have been a warning log, and a warning log is what each of them would have
 * been in a module that did not have three broken consumers as its motivating evidence. A warning is read
 * once, by whoever ran the deploy, on a terminal that is closed a minute later; the failures below are all
 * silent in production and all present as data being wrong rather than as anything failing.
 *
 * <p>{@code InitializingBean} rather than a {@code @PostConstruct}: the failure has to happen before any
 * listener container starts, and bean initialisation order gives that for free because every container
 * factory is built from a bean this one is a peer of. A validator that ran on
 * {@code ApplicationReadyEvent} would run after the first record had been consumed.
 */
@Slf4j
public class MessagingConfigurationValidator implements InitializingBean {

    /** The name reported for a conflict that is in the defaults rather than in one consumer's block. */
    static final String DEFAULTS = "<defaults>";

    private final MessagingProperties properties;
    private final DedupTransactionPolicy dedupTransaction;

    /**
     * Creates the validator.
     *
     * @param properties       this module's configuration
     * @param dedupTransaction the resolved dedup transaction policy
     */
    public MessagingConfigurationValidator(MessagingProperties properties,
                                           DedupTransactionPolicy dedupTransaction) {
        this.properties = properties;
        this.dedupTransaction = dedupTransaction;
    }

    @Override
    public void afterPropertiesSet() {
        checkOrdering(DEFAULTS, properties.resolve(DEFAULTS), properties.getDefaults());
        for (Map.Entry<String, ConsumerSettings> entry : properties.getConsumers().entrySet()) {
            checkOrdering(entry.getKey(), properties.resolve(entry.getKey()), entry.getValue());
        }
        log.debug("Messaging configuration validated: dedup container transaction {} ({})",
                dedupTransaction.containerTransaction() ? "on" : "off", dedupTransaction.reason());
    }

    /**
     * Refuses non-blocking retries for a consumer that relies on ordering, and refuses them without
     * topics.
     *
     * <p>The first is the real check, and the brief this module was written to asked for it explicitly
     * rather than as a warning. Non-blocking retries re-apply a failed record after records produced later
     * than it; the producer publishes an {@code orderingKey} as a promise that same-key events are applied
     * in order; the combination breaks that promise, and the symptom is a corrected value that reverts
     * hours later - the hardest class of bug to attribute, because by then the retry topic is empty and
     * the logs have rolled.
     *
     * <p>The second looks pedantic and is not. A {@code RetryTopicConfiguration} with no included topics
     * applies to nothing, so the opt-in appears to have worked, the blocking handler keeps doing the
     * retrying, and a team believes it has non-blocking retries for as long as nobody measures throughput.
     */
    private void checkOrdering(String name, ResolvedConsumerSettings resolved, ConsumerSettings declared) {
        if (!resolved.nonBlocking()) {
            return;
        }
        if (DEFAULTS.equals(name)) {
            throw new IllegalStateException(MessagingProperties.PREFIX + ".defaults.retry.non-blocking is"
                    + " set, which would opt every consumer in at once. Non-blocking retries are per-topic"
                    + " by design, because giving up ordering is a decision about one topic's semantics:"
                    + " set them under " + MessagingProperties.PREFIX + ".consumers.<name> for the"
                    + " consumers that have declared ordered=false and listed their topics.");
        }
        if (resolved.ordered()) {
            throw new OrderingConflictsWithRetryTopicsException(name);
        }
        if (declared == null || declared.getTopics().isEmpty()) {
            throw new IllegalStateException("Consumer '" + name + "' opts into non-blocking retries but"
                    + " lists no topics. Set " + MessagingProperties.PREFIX + ".consumers." + name
                    + ".topics, because a retry-topic configuration is declared independently of any"
                    + " listener - without them it applies to no topic and the opt-in silently does"
                    + " nothing while appearing to have worked.");
        }
    }
}
