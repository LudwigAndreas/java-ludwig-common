package ru.ludwigandreas.identity.kafka;

import java.util.LinkedHashMap;
import java.util.Map;
import ru.ludwigandreas.identity.kafka.config.IdentityKafkaProperties;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;

/**
 * The validated subscriptions: which topic feeds which source, and the group they are consumed with.
 */
public final class IdentityKafkaSubscriptions {

    private final Map<String, String> sourceByTopic;
    private final String[] topics;
    private final String groupId;

    /**
     * Validates the configuration.
     *
     * @param properties {@code ludwig.identity.kafka}
     * @throws SecurityConfigurationException naming the key when a subscription has no topic, two subscriptions
     *                                        share one, or the group is missing
     */
    public IdentityKafkaSubscriptions(IdentityKafkaProperties properties) {
        if (properties.getGroupId() == null || properties.getGroupId().isBlank()) {
            throw new SecurityConfigurationException(IdentityKafkaProperties.PREFIX + ".group-id is required: every"
                    + " service consumes the identity topics with its own group, or it receives only part of them");
        }
        Map<String, String> byTopic = new LinkedHashMap<>();
        properties.getSubscriptions().forEach((source, subscription) -> {
            String topic = subscription.getTopic();
            if (topic == null || topic.isBlank()) {
                throw new SecurityConfigurationException(IdentityKafkaProperties.PREFIX + ".subscriptions." + source
                        + ".topic is required");
            }
            String previous = byTopic.putIfAbsent(topic, source);
            if (previous != null) {
                throw new SecurityConfigurationException("Topic " + topic + " is subscribed for both '" + previous
                        + "' and '" + source + "'; a topic feeds one source");
            }
        });
        this.sourceByTopic = Map.copyOf(byTopic);
        this.topics = byTopic.keySet().toArray(String[]::new);
        this.groupId = properties.getGroupId();
    }

    /**
     * Every subscribed topic, for the listener's annotation. The same array on every call, so a document generator
     * that names a channel after the expression's value names it once.
     *
     * @return the topics; not to be modified
     */
    public String[] topics() {
        return topics;
    }

    /**
     * The source a topic is subscribed for.
     *
     * @param topic a subscribed topic
     * @return its source
     * @throws IllegalStateException for a topic nothing subscribed, which the container cannot deliver
     */
    public String sourceOf(String topic) {
        String source = sourceByTopic.get(topic);
        if (source == null) {
            throw new IllegalStateException("No identity subscription for topic " + topic);
        }
        return source;
    }

    /**
     * The consumer group.
     *
     * @return the group id
     */
    public String groupId() {
        return groupId;
    }
}
