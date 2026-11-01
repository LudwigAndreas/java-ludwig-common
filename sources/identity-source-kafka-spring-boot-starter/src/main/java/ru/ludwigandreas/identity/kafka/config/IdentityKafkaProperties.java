package ru.ludwigandreas.identity.kafka.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The Kafka feed's configuration, under {@code ludwig.identity.kafka}.
 */
@Validated
@ConfigurationProperties(prefix = IdentityKafkaProperties.PREFIX)
public class IdentityKafkaProperties {

    /** The configuration prefix. */
    public static final String PREFIX = "ludwig.identity.kafka";

    /** Whether the feed consumes at all. Off: the projection's tables are still read. */
    private boolean enabled = true;

    /**
     * The consumer group. Required whenever a subscription exists: every service keeps its own complete copy, and a
     * group shared with another service would receive only part of the stream.
     */
    private String groupId;

    /** Source name (declared under {@code ludwig.identity.sources}) -> the topic its provider publishes to. */
    private Map<String, @Valid Subscription> subscriptions = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public Map<String, Subscription> getSubscriptions() {
        return subscriptions;
    }

    public void setSubscriptions(Map<String, Subscription> subscriptions) {
        this.subscriptions = subscriptions;
    }

    /** One source's topic. */
    public static class Subscription {

        /** The topic the source's provider publishes user changes to. */
        @NotBlank
        private String topic;

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }
    }
}
