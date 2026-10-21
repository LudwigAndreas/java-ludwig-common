package ru.ludwigandreas.messaging.unit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import ru.ludwigandreas.messaging.config.MessagingConfigurationValidator;
import ru.ludwigandreas.messaging.error.OrderingConflictsWithRetryTopicsException;
import ru.ludwigandreas.messaging.settings.ConsumerSettings;
import ru.ludwigandreas.messaging.settings.DedupTransactionPolicy;
import ru.ludwigandreas.messaging.settings.KafkaClusters;
import ru.ludwigandreas.messaging.settings.MessagingProperties;

/**
 * The startup refusals.
 *
 * <p>The brief this module was written to asked for the ordering check to be "a real check, not a warning
 * log", and this is the test of that: the context does not start. A warning log would be read once, by
 * whoever ran the deploy, and the resulting bug - a corrected value that reverts hours later - would be
 * attributed to anything but its cause.
 */
class MessagingConfigurationValidatorTest {

    private static final DedupTransactionPolicy OFF = new DedupTransactionPolicy(false, "test");

    @Test
    @DisplayName("the default configuration starts")
    void acceptsTheDefaults() {
        assertThatCode(() -> validate(new MessagingProperties())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("non-blocking retries are refused for a consumer that relies on ordering")
    void refusesNonBlockingRetriesForAnOrderedConsumer() {
        MessagingProperties properties = new MessagingProperties();
        ConsumerSettings own = new ConsumerSettings();
        own.getRetry().setNonBlocking(true);
        own.setTopics(List.of("orders"));
        properties.getConsumers().put("orders-consumer", own);

        assertThatThrownBy(() -> validate(properties))
                .isInstanceOf(OrderingConflictsWithRetryTopicsException.class)
                .hasMessageContaining("orders-consumer")
                .hasMessageContaining("ordered=false");
    }

    @Test
    @DisplayName("non-blocking retries are accepted once the consumer declares it gives up ordering")
    void acceptsNonBlockingRetriesForAnUnorderedConsumer() {
        MessagingProperties properties = new MessagingProperties();
        ConsumerSettings own = new ConsumerSettings();
        own.getRetry().setNonBlocking(true);
        own.setOrdered(false);
        own.setTopics(List.of("orders"));
        properties.getConsumers().put("orders-consumer", own);

        assertThatCode(() -> validate(properties)).doesNotThrowAnyException();
    }

    /**
     * A retry-topic configuration with no included topics applies to nothing, so the opt-in appears to have
     * worked while the blocking handler keeps doing the retrying.
     */
    @Test
    @DisplayName("the opt-in is refused without topics, because it would silently do nothing")
    void refusesNonBlockingRetriesWithoutTopics() {
        MessagingProperties properties = new MessagingProperties();
        ConsumerSettings own = new ConsumerSettings();
        own.getRetry().setNonBlocking(true);
        own.setOrdered(false);
        properties.getConsumers().put("orders-consumer", own);

        assertThatThrownBy(() -> validate(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lists no topics");
    }

    @Test
    @DisplayName("non-blocking retries cannot be turned on for everybody at once in the defaults")
    void refusesNonBlockingRetriesInTheDefaults() {
        MessagingProperties properties = new MessagingProperties();
        properties.getDefaults().getRetry().setNonBlocking(true);
        properties.getDefaults().setOrdered(false);

        assertThatThrownBy(() -> validate(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("per-topic");
    }

    @Test
    @DisplayName("a consumer naming an undeclared cluster is refused, naming both")
    void refusesAnUndeclaredCluster() {
        MessagingProperties properties = new MessagingProperties();
        ConsumerSettings orders = new ConsumerSettings();
        orders.setCluster("partner-c");
        properties.getConsumers().put("orders-ingress", orders);

        assertThatThrownBy(() -> validate(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("orders-ingress").hasMessageContaining("partner-c");
    }

    @Test
    @DisplayName("a dead-letter cluster that is not declared is refused")
    void refusesAnUndeclaredDeadLetterCluster() {
        MessagingProperties properties = new MessagingProperties();
        ConsumerSettings orders = new ConsumerSettings();
        orders.getDeadLetter().setCluster("nowhere");
        properties.getConsumers().put("orders-ingress", orders);

        assertThatThrownBy(() -> validate(properties))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("nowhere");
    }

    @Test
    @DisplayName("retry topics are refused on a cluster other than default")
    void refusesRetryTopicsOffTheDefaultCluster() {
        MessagingProperties properties = new MessagingProperties();
        ConsumerSettings orders = new ConsumerSettings();
        orders.setCluster("partner-a");
        orders.setOrdered(false);
        orders.setTopics(List.of("orders"));
        orders.getRetry().setNonBlocking(true);
        properties.getConsumers().put("orders-ingress", orders);

        assertThatThrownBy(() -> validate(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("orders-ingress").hasMessageContaining("default");
    }

    @Test
    @DisplayName("a consumer on a declared cluster, dead-lettering to another, is accepted")
    void acceptsDeclaredClusters() {
        MessagingProperties properties = new MessagingProperties();
        ConsumerSettings orders = new ConsumerSettings();
        orders.setCluster("partner-a");
        orders.getDeadLetter().setCluster("internal");
        properties.getConsumers().put("orders-ingress", orders);

        assertThatCode(() -> validate(properties)).doesNotThrowAnyException();
    }

    private static void validate(MessagingProperties properties) {
        KafkaClusters clusters = KafkaClusters.bind(new Binder(new MapConfigurationPropertySource(Map.of(
                "ludwig.kafka.clusters.partner-a.bootstrap-servers", "a:9093",
                "ludwig.kafka.clusters.internal.bootstrap-servers", "i:9092"))), new KafkaProperties());
        new MessagingConfigurationValidator(properties, OFF, clusters).afterPropertiesSet();
    }
}
