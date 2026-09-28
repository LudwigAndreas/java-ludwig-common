package ru.ludwigandreas.messaging.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.EnableKafkaRetryTopic;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.retrytopic.RetryTopicConfiguration;
import org.springframework.kafka.retrytopic.RetryTopicConfigurationBuilder;
import org.springframework.kafka.retrytopic.RetryTopicSchedulerWrapper;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import ru.ludwigandreas.messaging.api.DeadLetterTopics;
import ru.ludwigandreas.messaging.settings.ConsumerSettings;
import ru.ludwigandreas.messaging.settings.MessagingProperties;
import ru.ludwigandreas.messaging.settings.ResolvedConsumerSettings;

/**
 * Non-blocking retry topics, for the consumers that opted in.
 *
 * <p>Shipped, because the brief asked for them and because there are real topics where throughput matters
 * more than order - an audit fan-out, a search index feed, anything keyed by a value nobody updates twice.
 * <b>Not the default</b>, and the reason belongs in code rather than only in a README:
 *
 * <p>A blocking {@code DefaultErrorHandler} pauses the partition while it retries. That is head-of-line
 * blocking, and it is what preserves order. Non-blocking retry topics keep the partition moving by
 * republishing the failed record to a retry topic, and the record is therefore re-applied <em>after</em>
 * records that were produced later than it. The producer side of this platform deliberately publishes an
 * {@code orderingKey} on every event, which is a promise that same-key events are applied in order.
 * Switching a topic to non-blocking retries breaks that promise silently, and the symptom is a value that
 * was corrected and reverts hours later - the hardest class of bug to attribute, because by the time
 * anybody looks the retry topic is empty and the logs have rolled.
 *
 * <p>So the opt-in is two explicit steps, and it is refused at startup for a consumer that has not also
 * declared {@code ordered: false} - see {@code MessagingConfigurationValidator}. The module-level switch
 * exists in addition to the per-consumer flag because {@code @EnableKafkaRetryTopic} changes how
 * <em>every</em> annotated listener in the application is bootstrapped, which is not something a
 * per-consumer property should be able to do to a service that was not expecting it.
 */
@Slf4j
@AutoConfiguration(after = MessagingAutoConfiguration.class)
@ConditionalOnClass({KafkaListener.class, RetryTopicConfiguration.class})
@ConditionalOnBean(KafkaTemplate.class)
@ConditionalOnProperty(prefix = MessagingProperties.PREFIX, name = "retry-topics.enabled",
        havingValue = "true")
@EnableConfigurationProperties(MessagingProperties.class)
@EnableKafkaRetryTopic
public class MessagingRetryTopicAutoConfiguration {

    /**
     * The scheduler the non-blocking machinery needs, when the application has none of its own.
     *
     * <p>Without it, flipping {@code retry-topics.enabled} fails the context with
     * {@code "Either a RetryTopicSchedulerWrapper or TaskScheduler bean is required"} - a message that names
     * nothing about this module or about the property that caused it, and that a team would spend an
     * afternoon on. Retry topics are timed: a record goes to a retry topic and its consumer is paused until
     * the backoff has elapsed, which is what the scheduler does.
     *
     * <p>A wrapper rather than a bare {@code TaskScheduler} bean, on purpose. A bare one would be a second
     * {@code TaskScheduler} in the context, and Spring's {@code @Scheduled} infrastructure resolves that by
     * type - so a module publishing one would silently move every scheduled method in the application onto
     * a thread pool this module sized. The wrapper is spring-kafka's own type for exactly this reason, and
     * nothing else looks for it.
     *
     * @return the scheduler wrapper
     */
    @Bean
    @ConditionalOnMissingBean({RetryTopicSchedulerWrapper.class,
            org.springframework.scheduling.TaskScheduler.class})
    public RetryTopicSchedulerWrapper ludwigRetryTopicScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        // One thread. The scheduler resumes paused partitions; it does no work of its own, and a pool would
        // only make the resume order nondeterministic.
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ludwig-retry-topic-");
        // Daemon, so a shutdown is not held up by a scheduler waiting for a backoff that will never matter
        // again.
        scheduler.setDaemon(true);
        return new RetryTopicSchedulerWrapper(scheduler);
    }

    /**
     * One retry-topic configuration covering every consumer that opted in.
     *
     * <p>One bean rather than one per consumer, because a {@code RetryTopicConfiguration} is keyed by the
     * topics it includes rather than by a listener, and two configurations including the same topic is a
     * conflict spring-kafka reports at a distance from the configuration that caused it. The backoff and
     * the attempt budget come from the resolved settings of the first opted-in consumer whose topics this
     * covers, which is exact as long as a topic belongs to one consumer - and a topic belonging to two
     * consumers with different retry budgets is a configuration this module cannot express either way.
     *
     * @param properties    this module's configuration
     * @param kafkaTemplate what republishes a record to its retry topic
     * @return the configuration, or one including no topics when nothing opted in
     */
    @Bean
    public RetryTopicConfiguration ludwigRetryTopicConfiguration(MessagingProperties properties,
                                                                 KafkaTemplate<Object, Object> kafkaTemplate) {
        List<String> topics = new ArrayList<>();
        ResolvedConsumerSettings first = null;
        for (Map.Entry<String, ConsumerSettings> entry : properties.getConsumers().entrySet()) {
            ResolvedConsumerSettings resolved = properties.resolve(entry.getKey());
            if (!resolved.nonBlocking()) {
                continue;
            }
            topics.addAll(entry.getValue().getTopics());
            first = first == null ? resolved : first;
        }
        if (first == null) {
            // The switch is on and nothing opted in. Not a failure - a deployment may enable the
            // infrastructure in one environment's configuration and opt consumers in per service - but
            // logged at warn, because the other reading is that somebody expected retries here and set the
            // per-consumer flag in the wrong place.
            log.warn("{}.retry-topics.enabled is true but no consumer sets retry.non-blocking;"
                    + " every consumer keeps blocking retries", MessagingProperties.PREFIX);
            first = properties.resolve(MessagingConfigurationValidator.DEFAULTS);
        }
        return RetryTopicConfigurationBuilder.newInstance()
                .includeTopics(List.copyOf(topics))
                .maxAttempts(first.maxAttempts())
                .exponentialBackoff(first.initialBackoff().toMillis(), first.multiplier(),
                        first.maxBackoff().toMillis())
                .dltSuffix(first.deadLetterSuffix())
                // The same destination the blocking path uses, so an operator has one topic to read
                // whichever retry strategy a consumer is on. DeadLetterTopics is the only declaration of
                // that suffix in the platform - see its class comment.
                .retryTopicSuffix(DeadLetterTopics.DEFAULT_SUFFIX.equals(first.deadLetterSuffix())
                        ? "-retry" : first.deadLetterSuffix() + "-retry")
                // Retry topics are not auto-created. A topic that springs into existence in dev and does
                // not exist in production is the same silent drop this module's dead-letter topics are
                // provisioned to avoid; see this module's README on provisioning.
                .doNotAutoCreateRetryTopics()
                .create(kafkaTemplate);
    }
}
