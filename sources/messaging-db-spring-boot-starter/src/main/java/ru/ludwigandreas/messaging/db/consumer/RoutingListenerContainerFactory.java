package ru.ludwigandreas.messaging.db.consumer;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Deserializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.config.AbstractKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpoint;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;
import org.springframework.kafka.listener.CompositeRecordInterceptor;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.support.TopicPartitionOffset;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.util.backoff.ExponentialBackOff;
import ru.ludwigandreas.messaging.db.settings.MessagingDbProperties;
import ru.ludwigandreas.messaging.dlt.AuditingDeadLetterRecoverer;
import ru.ludwigandreas.messaging.settings.ResolvedConsumerSettings;

/**
 * Stands in for a listener container factory bean and splits each endpoint between the log and Kafka.
 *
 * <p>Spring-kafka resolves {@code @KafkaListener(containerFactory = "...")} by name, as a
 * {@link KafkaListenerContainerFactory}, so replacing the bean with this type is invisible to the
 * listener and to the {@code @Bean} method that built the original. Every decision the original made -
 * deserializers, interceptors, error handling, dedup, transaction manager - is reused by asking it to
 * build a container for the endpoint and reading that container's configuration.
 */
public class RoutingListenerContainerFactory implements KafkaListenerContainerFactory<MessageListenerContainer> {

    private final AbstractKafkaListenerContainerFactory<?, ?, ?> delegate;
    private final String consumerName;
    private final String beanName;
    private final ObjectProvider<ConsumerSupport> support;

    /**
     * Creates the routing factory.
     *
     * @param delegate     the factory bean being replaced
     * @param consumerName the messaging consumer name it was built for, or {@code null} when it was not
     *                     built by messaging's builder
     * @param beanName     the replaced bean's name, for error messages
     * @param support      the shared collaborators, resolved when the first container is created
     */
    public RoutingListenerContainerFactory(AbstractKafkaListenerContainerFactory<?, ?, ?> delegate,
                                           String consumerName, String beanName,
                                           ObjectProvider<ConsumerSupport> support) {
        this.delegate = delegate;
        this.consumerName = consumerName;
        this.beanName = beanName;
        this.support = support;
    }

    /**
     * The factory this one stands in for.
     *
     * @return the original factory bean
     */
    public AbstractKafkaListenerContainerFactory<?, ?, ?> delegate() {
        return delegate;
    }

    @Override
    public MessageListenerContainer createListenerContainer(KafkaListenerEndpoint endpoint) {
        ConsumerSupport shared = support.getObject();
        MessagingDbProperties properties = shared.properties();
        Set<String> listed = properties.getConsume();
        refuseUnsupported(endpoint, listed, shared);

        List<String> topics = List.copyOf(endpoint.getTopics());
        List<String> logTopics = topics.stream().filter(listed::contains).toList();
        if (logTopics.isEmpty()) {
            return redirectDeadLetters(delegate.createListenerContainer(endpoint), shared);
        }
        // Built first, with the real endpoint, so that the factory configures the endpoint itself - see
        // NarrowedEndpoint. Never started on its own unless it doubles as the Kafka container below.
        AbstractMessageListenerContainer<?, ?> template =
                (AbstractMessageListenerContainer<?, ?>) delegate.createListenerContainer(endpoint);
        DbLogListenerContainer log = new DbLogListenerContainer(spec(endpoint, template, logTopics, shared));

        if (properties.getMode() == MessagingDbProperties.Mode.DRAINING) {
            // Draining: every topic is read from Kafka too, so the template is the Kafka container as built.
            return new CompositeListenerContainer(List.of(template, log));
        }
        List<String> kafkaTopics = topics.stream().filter(topic -> !listed.contains(topic)).toList();
        if (kafkaTopics.isEmpty()) {
            return log;
        }
        MessageListenerContainer kafka = redirectDeadLetters(
                delegate.createListenerContainer(new NarrowedEndpoint(endpoint, kafkaTopics)), shared);
        return new CompositeListenerContainer(List.of(kafka, log));
    }

    @Override
    public MessageListenerContainer createContainer(TopicPartitionOffset... topicPartitions) {
        return delegate.createContainer(topicPartitions);
    }

    @Override
    public MessageListenerContainer createContainer(String... topics) {
        return delegate.createContainer(topics);
    }

    @Override
    public MessageListenerContainer createContainer(Pattern topicPattern) {
        return delegate.createContainer(topicPattern);
    }

    private void refuseUnsupported(KafkaListenerEndpoint endpoint, Set<String> listed, ConsumerSupport shared) {
        Pattern pattern = endpoint.getTopicPattern();
        if (pattern != null) {
            listed.stream().filter(topic -> pattern.matcher(topic).matches()).findFirst().ifPresent(topic -> {
                throw new IllegalStateException("Listener '" + endpoint.getId() + "' on container factory '"
                        + beanName + "' subscribes by pattern " + pattern + ", which matches '" + topic
                        + "' listed in ludwig.messaging.db.consume; a pattern cannot be resolved against the log."
                        + " Name the topics instead.");
            });
        }
        boolean readsTheLog = endpoint.getTopics().stream().anyMatch(listed::contains);
        if (!readsTheLog) {
            return;
        }
        if (consumerName == null) {
            throw new IllegalStateException("Listener '" + endpoint.getId() + "' reads " + endpoint.getTopics()
                    + " from the log, but its container factory '" + beanName + "' was not built by"
                    + " ListenerContainerFactoryBuilder, so it has no consumer settings to apply there.");
        }
        if (shared.messaging().resolve(consumerName).nonBlocking()) {
            throw new IllegalStateException("Consumer '" + consumerName + "' enables non-blocking retry topics and"
                    + " reads " + endpoint.getTopics().stream().filter(listed::contains).toList()
                    + " from the log; retry topics are not supported on log topics.");
        }
    }

    @SuppressWarnings("unchecked")
    private DbLogListenerContainer.Spec spec(KafkaListenerEndpoint endpoint,
                                             AbstractMessageListenerContainer<?, ?> template,
                                             List<String> logTopics, ConsumerSupport shared) {
        ConsumerFactory<?, ?> consumerFactory = delegate.getConsumerFactory();
        if (!(consumerFactory instanceof DefaultKafkaConsumerFactory<?, ?> defaults)
                || defaults.getKeyDeserializer() == null || defaults.getValueDeserializer() == null) {
            throw new IllegalStateException("Container factory '" + beanName + "' has no deserializer instances to"
                    + " read the log with; build it with ListenerContainerFactoryBuilder.");
        }
        String groupId = template.getGroupId();
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalStateException("Listener '" + endpoint.getId() + "' reads " + logTopics
                    + " from the log and has no group id; a log position is kept per group.");
        }
        int concurrency = template instanceof ConcurrentMessageListenerContainer<?, ?> concurrent
                ? concurrent.getConcurrency() : 1;
        return new DbLogListenerContainer.Spec(shared.store(), shared, shared.messaging().resolve(consumerName),
                groupId, template.getListenerId(), logTopics, earliest(endpoint, consumerFactory), concurrency,
                template.getContainerProperties().getMessageListener(),
                (Deserializer<Object>) defaults.getKeyDeserializer(),
                (Deserializer<Object>) defaults.getValueDeserializer(),
                interceptorFor(shared, consumerName), template.getContainerProperties(), template.isAutoStartup(),
                shared.properties().getPollInterval());
    }

    /**
     * Messaging's per-cluster activity interceptor, then every interceptor in the context, as the builder
     * composes them.
     */
    @SuppressWarnings("unchecked")
    private static RecordInterceptor<Object, Object> interceptorFor(ConsumerSupport shared, String consumerName) {
        RecordInterceptor<Object, Object> activity = shared.activityMonitor()
                .forCluster(shared.messaging().resolve(consumerName).cluster());
        return shared.interceptor() == null ? activity
                : new CompositeRecordInterceptor<>(activity, shared.interceptor());
    }

    /** {@code auto.offset.reset}, from the endpoint's own properties first, as the Kafka consumer resolves it. */
    private static boolean earliest(KafkaListenerEndpoint endpoint, ConsumerFactory<?, ?> consumerFactory) {
        Properties overrides = endpoint.getConsumerProperties();
        Object reset = overrides != null ? overrides.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG) : null;
        if (reset == null) {
            Map<String, Object> config = consumerFactory.getConfigurationProperties();
            reset = config.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG);
        }
        return "earliest".equals(String.valueOf(reset));
    }

    /**
     * While active, a record read from Kafka is dead-lettered into the log, because there is no cluster
     * this side can write to. The handler is messaging's - same backoff, same non-retryable classes, same
     * audited recoverer - with only the write replaced.
     */
    private MessageListenerContainer redirectDeadLetters(MessageListenerContainer container, ConsumerSupport shared) {
        if (shared.properties().getMode() != MessagingDbProperties.Mode.ACTIVE || consumerName == null
                || !(container instanceof AbstractMessageListenerContainer<?, ?> kafka)) {
            return container;
        }
        ResolvedConsumerSettings settings = shared.messaging().resolve(consumerName);
        if (!settings.deadLetterEnabled()) {
            return container;
        }
        ExponentialBackOff backOff =
                new ExponentialBackOff(settings.initialBackoff().toMillis(), settings.multiplier());
        backOff.setMaxInterval(settings.maxBackoff().toMillis());
        // maxAttempts - 1: ExponentialBackOff counts retries, not deliveries. ListenerContainerFactoryBuilder
        // explains the arithmetic and its history; the two must stay equal.
        backOff.setMaxAttempts(settings.maxAttempts() - 1);
        DefaultErrorHandler handler = new DefaultErrorHandler(new AuditingDeadLetterRecoverer(
                (record, exception) -> shared.deadLetters().write(record.topic(), record.partition(), record.offset(),
                        record.key(), record.value(), record.headers(), exception, settings.deadLetterSuffix()),
                shared.envelopeReader(), shared.metrics(), shared.auditSink(), shared.clock(),
                settings.cluster(), settings.deadLetterSuffix(), settings.maxAttempts()), backOff);
        handler.addNotRetryableExceptions(DeserializationException.class, MessageConversionException.class);
        handler.setRetryListeners((record, exception, attempt) ->
                shared.metrics().recordRetry(settings.cluster(), record.topic()));
        kafka.setCommonErrorHandler(handler);
        return container;
    }
}
