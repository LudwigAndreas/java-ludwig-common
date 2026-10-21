package ru.ludwigandreas.messaging.db.consumer;

import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;
import org.springframework.kafka.config.ContainerPostProcessor;
import org.springframework.kafka.config.KafkaListenerEndpoint;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.TopicPartitionOffset;
import org.springframework.kafka.support.converter.MessageConverter;

/**
 * An endpoint identical to another except for the topics it subscribes to.
 *
 * <p>Used only after the real endpoint has been through the platform's factory once, because the factory
 * configures an endpoint - its dedup filter among other things - only when it is spring-kafka's own
 * endpoint type. By then the real endpoint is configured, and this one forwards its listener setup to it.
 */
class NarrowedEndpoint implements KafkaListenerEndpoint {

    private final KafkaListenerEndpoint delegate;
    private final List<String> topics;

    NarrowedEndpoint(KafkaListenerEndpoint delegate, List<String> topics) {
        this.delegate = delegate;
        this.topics = List.copyOf(topics);
    }

    @Override
    public Collection<String> getTopics() {
        return topics;
    }

    @Override
    public String getId() {
        return delegate.getId();
    }

    @Override
    public String getGroupId() {
        return delegate.getGroupId();
    }

    @Override
    public String getGroup() {
        return delegate.getGroup();
    }

    @Override
    public TopicPartitionOffset[] getTopicPartitionsToAssign() {
        return delegate.getTopicPartitionsToAssign();
    }

    @Override
    public Pattern getTopicPattern() {
        return delegate.getTopicPattern();
    }

    @Override
    public String getClientIdPrefix() {
        return delegate.getClientIdPrefix();
    }

    @Override
    public Integer getConcurrency() {
        return delegate.getConcurrency();
    }

    @Override
    public Boolean getAutoStartup() {
        return delegate.getAutoStartup();
    }

    @Override
    public Properties getConsumerProperties() {
        return delegate.getConsumerProperties();
    }

    @Override
    public void setupListenerContainer(MessageListenerContainer listenerContainer, MessageConverter messageConverter) {
        delegate.setupListenerContainer(listenerContainer, messageConverter);
    }

    @Override
    public boolean isSplitIterables() {
        return delegate.isSplitIterables();
    }

    @Override
    public byte[] getListenerInfo() {
        return delegate.getListenerInfo();
    }

    @Override
    public String getMainListenerId() {
        return delegate.getMainListenerId();
    }

    @Override
    public Boolean getBatchListener() {
        return delegate.getBatchListener();
    }

    @Override
    public ContainerPostProcessor<?, ?, ?> getContainerPostProcessor() {
        return delegate.getContainerPostProcessor();
    }
}
