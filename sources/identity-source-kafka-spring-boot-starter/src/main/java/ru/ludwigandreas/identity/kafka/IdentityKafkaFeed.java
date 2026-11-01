package ru.ludwigandreas.identity.kafka;

import java.util.Map;
import ru.ludwigandreas.identity.feed.IdentityFeed;

/**
 * One subscription, as the projection's startup validation and sources listing see it.
 *
 * @param source  the subscribed source
 * @param topic   its topic
 * @param groupId the consumer group
 */
public record IdentityKafkaFeed(String source, String topic, String groupId) implements IdentityFeed {

    /** The container factory the identity listener runs on. */
    public static final String CONTAINER_FACTORY = "identityKafkaListenerContainerFactory";

    /** The name this consumer's settings live under in {@code ludwig.messaging.consumers.*}. */
    public static final String CONSUMER_NAME = "identity-projection";

    @Override
    public String kind() {
        return "kafka";
    }

    @Override
    public Map<String, Object> status() {
        return Map.of("topic", topic, "groupId", groupId);
    }
}
