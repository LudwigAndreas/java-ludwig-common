package ru.ludwigandreas.messaging.db.consumer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * One endpoint's containers on both transports, started, stopped and reported as one.
 *
 * <p>The first container is the one whose properties, group and listener id the registry sees; the
 * routing factory puts the Kafka container first whenever there is one.
 */
public class CompositeListenerContainer implements MessageListenerContainer {

    private final List<MessageListenerContainer> containers;

    /**
     * Creates the composite.
     *
     * @param containers the containers, at least two
     */
    public CompositeListenerContainer(List<MessageListenerContainer> containers) {
        this.containers = List.copyOf(containers);
    }

    /**
     * The containers this composite manages.
     *
     * @return the containers, in start order
     */
    public List<MessageListenerContainer> containers() {
        return containers;
    }

    @Override
    public void start() {
        containers.forEach(MessageListenerContainer::start);
    }

    @Override
    public void stop() {
        containers.forEach(MessageListenerContainer::stop);
    }

    @Override
    public boolean isRunning() {
        return containers.stream().anyMatch(MessageListenerContainer::isRunning);
    }

    @Override
    public boolean isAutoStartup() {
        return containers.get(0).isAutoStartup();
    }

    @Override
    public void setAutoStartup(boolean autoStartup) {
        containers.forEach(container -> container.setAutoStartup(autoStartup));
    }

    @Override
    public void setupMessageListener(Object messageListener) {
        // Each container was set up by its own factory call.
    }

    @Override
    public Map<String, Map<MetricName, ? extends Metric>> metrics() {
        Map<String, Map<MetricName, ? extends Metric>> all = new HashMap<>();
        containers.forEach(container -> all.putAll(container.metrics()));
        return all;
    }

    @Override
    public ContainerProperties getContainerProperties() {
        return containers.get(0).getContainerProperties();
    }

    @Override
    public String getGroupId() {
        return containers.get(0).getGroupId();
    }

    @Override
    public String getListenerId() {
        return containers.get(0).getListenerId();
    }
}
