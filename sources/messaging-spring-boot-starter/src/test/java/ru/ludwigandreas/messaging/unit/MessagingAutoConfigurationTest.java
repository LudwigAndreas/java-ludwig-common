package ru.ludwigandreas.messaging.unit;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.adapter.RecordFilterStrategy;
import org.springframework.test.util.ReflectionTestUtils;
import ru.ludwigandreas.messaging.config.MessagingAutoConfiguration;
import ru.ludwigandreas.messaging.config.MessagingMetricsAutoConfiguration;
import ru.ludwigandreas.messaging.config.MessagingWebAutoConfiguration;
import ru.ludwigandreas.messaging.consumer.ListenerContainerFactoryBuilder;
import ru.ludwigandreas.messaging.metrics.MessagingMetrics;
import ru.ludwigandreas.messaging.metrics.MicrometerMessagingMetrics;
import ru.ludwigandreas.messaging.metrics.NoopMessagingMetrics;
import ru.ludwigandreas.messaging.settings.DedupTransactionPolicy;

/** What a deployment actually gets, resolved through the conditions rather than constructed by hand. */
class MessagingAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class,
                    MessagingMetricsAutoConfiguration.class, MessagingAutoConfiguration.class,
                    MessagingWebAutoConfiguration.class));

    @Test
    @DisplayName("a deployment that configures nothing gets the builder and the no-op metrics")
    void wiresTheBuilder() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ListenerContainerFactoryBuilder.class);
            assertThat(context).getBean(MessagingMetrics.class).isInstanceOf(NoopMessagingMetrics.class);
        });
    }

    @Test
    @DisplayName("switching the module off wires nothing")
    void backsOffWhenDisabled() {
        runner.withPropertyValues("ludwig.messaging.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ListenerContainerFactoryBuilder.class));
    }

    @Test
    @DisplayName("Micrometer replaces the no-op metrics")
    void bindsMicrometerWhenPresent() {
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> assertThat(context).getBean(MessagingMetrics.class)
                        .isInstanceOf(MicrometerMessagingMetrics.class));
    }

    /**
     * The rule the whole module exists for, asserted on the object rather than on the source: a factory
     * built here has an error handler. A factory without one inherits Boot's default, which retries ten
     * times with no backoff and then drops the record.
     */
    @Test
    @DisplayName("every factory the builder returns has an error handler and per-record acknowledgement")
    void everyFactoryHasAnErrorHandler() {
        runner.run(context -> {
            ConcurrentKafkaListenerContainerFactory<String, String> factory =
                    context.getBean(ListenerContainerFactoryBuilder.class)
                            .forTextPayload("projection", "orders");

            assertThat(errorHandlerOf(factory)).isNotNull();
            assertThat(factory.getContainerProperties().getAckMode())
                    .isEqualTo(ContainerProperties.AckMode.RECORD);
        });
    }

    /**
     * No {@code KafkaTemplate} bean either, for the same reason there is no {@code Clock} bean: a module that
     * publishes one makes every raw or {@code Object}-typed {@code KafkaTemplate} injection in the
     * application ambiguous. The dead-letter producer is built inside the factory builder, where nothing else
     * can see it; what it serializes is asserted in {@code DeadLetterTemplatesTest}, which is where that
     * behaviour lives.
     */
    @Test
    @DisplayName("no KafkaTemplate bean is published beyond the application's own")
    void publishesNoExtraKafkaTemplate() {
        runner.run(context -> assertThat(context).hasSingleBean(KafkaTemplate.class));
    }

    /**
     * Dedup by configuration rather than by remembering: the filter is attached to every factory this module
     * builds as soon as one exists in the context, with no per-consumer wiring.
     */
    @Test
    @DisplayName("a record filter strategy in the context is attached without anybody wiring it")
    void attachesTheDedupFilter() {
        runner.withUserConfiguration(WithFilter.class).run(context -> {
            ConcurrentKafkaListenerContainerFactory<String, String> factory =
                    context.getBean(ListenerContainerFactoryBuilder.class)
                            .forTextPayload("projection", "orders");
            assertThat(filterOf(factory)).isNotNull();
        });
    }

    @Test
    @DisplayName("a consumer can opt out of dedup, which a converging projection should")
    void honoursAPerConsumerDedupOptOut() {
        runner.withUserConfiguration(WithFilter.class)
                .withPropertyValues("ludwig.messaging.consumers.projection.dedup=false")
                .run(context -> assertThat(filterOf(context.getBean(ListenerContainerFactoryBuilder.class)
                        .forTextPayload("projection", "orders"))).isNull());
    }

    /**
     * Without the idempotency starter there is no claim mode to read, so the explicit property is the whole
     * answer and its default is off - attaching a transaction manager changes the semantics of every
     * listener on a container, which a module must not do on a guess.
     */
    @Test
    @DisplayName("the fallback dedup transaction policy is off and says why")
    void fallsBackToNoContainerTransaction() {
        runner.run(context -> {
            DedupTransactionPolicy policy = context.getBean(DedupTransactionPolicy.class);
            assertThat(policy.containerTransaction()).isFalse();
            assertThat(policy.reason()).contains("no idempotency starter");
        });
    }

    /**
     * The regression test for a defect that broke {@code crud-service-example}'s context: this module used to
     * publish a {@code Clock} bean guarded by {@code @ConditionalOnMissingBean}, and
     * {@code rest-client-spring-boot-starter} publishes one too. Whichever autoconfiguration ran second
     * backed off, so the two together left two {@code Clock} beans in the context and every unqualified
     * {@code Clock} injection in the application ambiguous.
     */
    @Test
    @DisplayName("no Clock bean is published, and two in the context are tolerated")
    void publishesNoClockBean() {
        runner.run(context -> assertThat(context).doesNotHaveBean(Clock.class));

        runner.withBean("firstClock", Clock.class, Clock::systemUTC)
                .withBean("secondClock", Clock.class, Clock::systemUTC)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ListenerContainerFactoryBuilder.class);
                });
    }

    @Test
    @DisplayName("the problem mapper and bundle are contributed when web-core is present")
    void contributesToTheProblemPipeline() {
        runner.run(context -> {
            assertThat(context).hasBean("ludwigMessagingProblemMessages");
            assertThat(context).hasBean("ludwigUnsupportedEventVersionProblemMapper");
        });
    }

    /**
     * Read reflectively, because {@code AbstractKafkaListenerContainerFactory} exposes setters and no
     * getters. The alternative - starting a container and provoking a failure - is the integration suite's
     * job; what this asserts is that the factory was configured, which is a fact about the object.
     */
    private static CommonErrorHandler errorHandlerOf(ConcurrentKafkaListenerContainerFactory<?, ?> factory) {
        return (CommonErrorHandler) ReflectionTestUtils.getField(factory, "commonErrorHandler");
    }

    private static Object filterOf(ConcurrentKafkaListenerContainerFactory<?, ?> factory) {
        return ReflectionTestUtils.getField(factory, "recordFilterStrategy");
    }

    /** A filter strategy that discards nothing, standing in for the idempotency starter's. */
    @Configuration(proxyBeanMethods = false)
    static class WithFilter {

        @Bean
        RecordFilterStrategy<Object, Object> filter() {
            return (ConsumerRecord<Object, Object> record) -> false;
        }
    }
}
