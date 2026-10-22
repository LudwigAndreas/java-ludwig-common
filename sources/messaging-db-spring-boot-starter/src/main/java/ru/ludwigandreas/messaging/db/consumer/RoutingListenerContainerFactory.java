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
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.support.TopicPartitionOffset;
import ru.ludwigandreas.messaging.consumer.DelegatingListenerContainerFactory;
import ru.ludwigandreas.messaging.db.settings.MessagingDbProperties;
import ru.ludwigandreas.messaging.settings.KafkaClusters;
import ru.ludwigandreas.messaging.settings.ResolvedConsumerSettings;

/**
 * Stands in for a listener container factory bean and gives a consumer on a replaced cluster a container
 * that reads the log.
 *
 * <p>Spring-kafka resolves {@code @KafkaListener(containerFactory = "...")} by name, as a
 * {@link KafkaListenerContainerFactory}, so replacing the bean with this type is invisible to the
 * listener and to the {@code @Bean} method that built the original. Every decision the original made -
 * deserializers, interceptors, error handling, dedup, transaction manager - is reused by asking it to
 * build a container for the endpoint and reading that container's configuration.
 */
public class RoutingListenerContainerFactory
        implements KafkaListenerContainerFactory<MessageListenerContainer>, DelegatingListenerContainerFactory {

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
    @Override
    public AbstractKafkaListenerContainerFactory<?, ?, ?> delegate() {
        return delegate;
    }

    @Override
    public MessageListenerContainer createListenerContainer(KafkaListenerEndpoint endpoint) {
        ConsumerSupport shared = support.getObject();
        MessagingDbProperties properties = shared.properties();
        if (!readsTheLog(endpoint, properties.getClusters(), shared)) {
            return delegate.createListenerContainer(endpoint);
        }
        // Built with the real endpoint, so that the factory configures the endpoint itself - its dedup filter
        // among other things - and its configuration is read off the result. Never started while active.
        AbstractMessageListenerContainer<?, ?> template =
                (AbstractMessageListenerContainer<?, ?>) delegate.createListenerContainer(endpoint);
        DbLogListenerContainer log = new DbLogListenerContainer(spec(endpoint, template, shared));
        if (properties.getMode() == MessagingDbProperties.Mode.DRAINING) {
            // Draining: the cluster is read too, so the template is the Kafka container as built.
            return new CompositeListenerContainer(List.of(template, log));
        }
        return log;
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

    /**
     * Whether the endpoint's consumer is on a replaced cluster, refusing what the log cannot serve.
     *
     * <p>A factory not built by messaging's builder has no consumer settings and reads {@code spring.kafka.*},
     * the {@value KafkaClusters#DEFAULT} cluster, so it is refused only when that cluster is replaced.
     */
    private boolean readsTheLog(KafkaListenerEndpoint endpoint, Set<String> replaced, ConsumerSupport shared) {
        if (consumerName == null) {
            if (replaced.contains(KafkaClusters.DEFAULT)) {
                throw new IllegalStateException("Listener '" + endpoint.getId() + "' on container factory '"
                        + beanName + "' reads cluster '" + KafkaClusters.DEFAULT + "', which the log replaces, but"
                        + " the factory was not built by ListenerContainerFactoryBuilder, so it has no consumer"
                        + " settings to apply there.");
            }
            return false;
        }
        ResolvedConsumerSettings settings = shared.messaging().resolve(consumerName);
        if (!replaced.contains(settings.cluster())) {
            return false;
        }
        if (endpoint.getTopicPattern() != null) {
            throw new IllegalStateException("Listener '" + endpoint.getId() + "' of consumer '" + consumerName
                    + "' subscribes by pattern " + endpoint.getTopicPattern() + " on cluster '" + settings.cluster()
                    + "', which the log replaces; a pattern cannot be resolved against the log. Name the topics.");
        }
        if (settings.nonBlocking()) {
            throw new IllegalStateException("Consumer '" + consumerName + "' enables non-blocking retry topics on"
                    + " cluster '" + settings.cluster() + "', which the log replaces; retry topics are not supported"
                    + " on the log.");
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private DbLogListenerContainer.Spec spec(KafkaListenerEndpoint endpoint,
                                             AbstractMessageListenerContainer<?, ?> template,
                                             ConsumerSupport shared) {
        List<String> logTopics = List.copyOf(endpoint.getTopics());
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
}
