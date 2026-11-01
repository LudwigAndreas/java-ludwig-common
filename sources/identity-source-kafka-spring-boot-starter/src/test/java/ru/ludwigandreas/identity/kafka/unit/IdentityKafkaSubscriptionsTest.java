package ru.ludwigandreas.identity.kafka.unit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.identity.kafka.IdentityKafkaSubscriptions;
import ru.ludwigandreas.identity.kafka.config.IdentityKafkaProperties;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;

class IdentityKafkaSubscriptionsTest {

    private static IdentityKafkaProperties properties(String groupId, String... sourceTopicPairs) {
        IdentityKafkaProperties properties = new IdentityKafkaProperties();
        properties.setGroupId(groupId);
        for (int i = 0; i < sourceTopicPairs.length; i += 2) {
            IdentityKafkaProperties.Subscription subscription = new IdentityKafkaProperties.Subscription();
            subscription.setTopic(sourceTopicPairs[i + 1]);
            properties.getSubscriptions().put(sourceTopicPairs[i], subscription);
        }
        return properties;
    }

    @Test
    @DisplayName("a subscription without a consumer group is refused, naming the key")
    void requiresAGroup() {
        assertThatThrownBy(() -> new IdentityKafkaSubscriptions(properties(null, "staff", "corp.staff.users")))
                .isInstanceOf(SecurityConfigurationException.class)
                .hasMessageContaining("ludwig.identity.kafka.group-id");
    }

    @Test
    @DisplayName("a subscription without a topic is refused, naming the subscription")
    void requiresATopic() {
        assertThatThrownBy(() -> new IdentityKafkaSubscriptions(properties("g", "staff", " ")))
                .isInstanceOf(SecurityConfigurationException.class)
                .hasMessageContaining("ludwig.identity.kafka.subscriptions.staff.topic");
    }

    @Test
    @DisplayName("one topic cannot feed two sources")
    void oneTopicOneSource() {
        assertThatThrownBy(() -> new IdentityKafkaSubscriptions(properties("g", "staff", "t", "customers", "t")))
                .isInstanceOf(SecurityConfigurationException.class).hasMessageContaining("'staff' and 'customers'");
    }
}
