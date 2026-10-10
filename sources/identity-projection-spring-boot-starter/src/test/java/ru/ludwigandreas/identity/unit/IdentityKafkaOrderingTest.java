package ru.ludwigandreas.identity.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.Configurations;
import ru.ludwigandreas.identity.config.IdentityKafkaAutoConfiguration;
import ru.ludwigandreas.identity.config.IdentityProjectionAutoConfiguration;
import ru.ludwigandreas.messaging.config.MessagingAutoConfiguration;

/**
 * The identity consumer's auto-configuration has to be evaluated after the messaging one.
 *
 * <p>Its listener container factory is declared behind
 * {@code @ConditionalOnBean(ListenerContainerFactoryBuilder.class)}, and a bean condition only sees
 * what has been registered so far. Spring Boot orders auto-configurations that say nothing about each
 * other alphabetically, and {@code ru.ludwigandreas.identity} sorts before
 * {@code ru.ludwigandreas.messaging}: with no explicit ordering the condition was evaluated first,
 * answered false, and an application with {@code ludwig.identity.kafka.enabled=true} failed to start on
 * the listener's missing factory bean.
 *
 * <p>This asserts the order through the same sorter Spring Boot applies, which is the contract. A
 * context that happened to start would prove less: it would pass just as well by accident of naming.
 */
class IdentityKafkaOrderingTest {

    @Test
    @DisplayName("the identity Kafka auto-configuration is ordered after the messaging one it depends on")
    void identityKafkaComesAfterMessaging() {
        // Given in the order that is wrong, so that only declared ordering can put it right.
        List<Class<?>> ordered = List.of(Configurations.getClasses(AutoConfigurations.of(
                IdentityKafkaAutoConfiguration.class,
                IdentityProjectionAutoConfiguration.class,
                MessagingAutoConfiguration.class)));

        assertThat(ordered.indexOf(MessagingAutoConfiguration.class))
                .as("MessagingAutoConfiguration registers the ListenerContainerFactoryBuilder that "
                        + "IdentityKafkaAutoConfiguration's bean condition looks for")
                .isLessThan(ordered.indexOf(IdentityKafkaAutoConfiguration.class));
        assertThat(ordered.indexOf(IdentityProjectionAutoConfiguration.class))
                .isLessThan(ordered.indexOf(IdentityKafkaAutoConfiguration.class));
    }
}
