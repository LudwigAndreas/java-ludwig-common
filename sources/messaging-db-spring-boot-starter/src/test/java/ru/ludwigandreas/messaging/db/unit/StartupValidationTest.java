package ru.ludwigandreas.messaging.db.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.MethodKafkaListenerEndpoint;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory;
import ru.ludwigandreas.audit.NoopAuditSink;
import ru.ludwigandreas.messaging.db.settings.MessagingDbProperties;
import ru.ludwigandreas.messaging.db.consumer.ConsumerSupport;
import ru.ludwigandreas.messaging.db.consumer.RoutingListenerContainerFactory;
import ru.ludwigandreas.messaging.settings.MessagingProperties;

/** The consumer shapes the log cannot serve are refused when the container is created, naming what to fix. */
class StartupValidationTest {

    private static final String LISTED = "orders";

    @Test
    void aPatternThatMatchesAListedTopicIsRefused() {
        MethodKafkaListenerEndpoint<String, String> endpoint = endpoint();
        endpoint.setTopicPattern(Pattern.compile("ord.*"));

        assertThatThrownBy(() -> factory("plain", Map.of()).createListenerContainer(endpoint))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ord.*").hasMessageContaining(LISTED);
    }

    @Test
    void retryTopicsOnALogTopicAreRefused() {
        MethodKafkaListenerEndpoint<String, String> endpoint = endpoint();
        endpoint.setTopics(LISTED);

        assertThatThrownBy(() -> factory("orders-ingress",
                Map.of("ludwig.messaging.consumers.orders-ingress.retry.non-blocking", "true"))
                .createListenerContainer(endpoint))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("orders-ingress").hasMessageContaining(LISTED);
    }

    @Test
    void aFactoryNotBuiltByThePlatformBuilderCannotReadTheLog() {
        MethodKafkaListenerEndpoint<String, String> endpoint = endpoint();
        endpoint.setTopics(LISTED);

        assertThatThrownBy(() -> factory(null, Map.of()).createListenerContainer(endpoint))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ListenerContainerFactoryBuilder");
    }

    @Test
    void anEndpointWithNoLogTopicIsLeftToKafka() {
        MethodKafkaListenerEndpoint<String, String> endpoint = endpoint();
        endpoint.setTopics("payments");

        assertThat(factory(null, Map.of()).createListenerContainer(endpoint))
                .isInstanceOf(ConcurrentMessageListenerContainer.class);
    }

    /** The endpoint {@code @KafkaListener} produces, built by hand. */
    private static MethodKafkaListenerEndpoint<String, String> endpoint() {
        MethodKafkaListenerEndpoint<String, String> endpoint = new MethodKafkaListenerEndpoint<>();
        endpoint.setId("listener-1");
        endpoint.setGroupId("group-1");
        Listener listener = new Listener();
        endpoint.setBean(listener);
        try {
            endpoint.setMethod(Listener.class.getMethod("on", String.class));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
        DefaultMessageHandlerMethodFactory methods = new DefaultMessageHandlerMethodFactory();
        methods.afterPropertiesSet();
        endpoint.setMessageHandlerMethodFactory(methods);
        return endpoint;
    }

    /** A listener with nothing in it; only its shape matters. */
    public static class Listener {

        /**
         * Receives a record.
         *
         * @param value the payload
         */
        public void on(String value) {
            // nothing to do
        }
    }

    private static RoutingListenerContainerFactory factory(String consumerName, Map<String, String> config) {
        MessagingProperties messaging = new Binder(new MapConfigurationPropertySource(config))
                .bindOrCreate(MessagingProperties.PREFIX, MessagingProperties.class);
        MessagingDbProperties properties = new MessagingDbProperties();
        properties.setConsume(Set.of(LISTED));
        properties.setPollInterval(Duration.ofSeconds(1));
        ConsumerSupport support = new ConsumerSupport(properties, messaging, null, null, null, new NoopAuditSink(),
                Clock.systemUTC(), null, null);
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("support", support);

        ConcurrentKafkaListenerContainerFactory<String, String> delegate =
                new ConcurrentKafkaListenerContainerFactory<>();
        delegate.setConsumerFactory(new DefaultKafkaConsumerFactory<>(Map.of()));
        return new RoutingListenerContainerFactory(delegate, consumerName, "testFactory",
                beans.getBeanProvider(ConsumerSupport.class));
    }
}
