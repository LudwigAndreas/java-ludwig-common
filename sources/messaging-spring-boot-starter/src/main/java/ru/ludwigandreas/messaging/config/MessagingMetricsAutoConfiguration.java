package ru.ludwigandreas.messaging.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import ru.ludwigandreas.messaging.metrics.MessagingMetrics;
import ru.ludwigandreas.messaging.metrics.MicrometerMessagingMetrics;
import ru.ludwigandreas.messaging.settings.MessagingProperties;

/**
 * Binds this module's counters and the silence gauge when Micrometer is present.
 *
 * <p>{@code before = MessagingAutoConfiguration.class} so that the Micrometer binding wins the
 * {@code @ConditionalOnMissingBean} race against the no-op one, which is the arrangement every metrics
 * module in this repository uses.
 *
 * <p>Consumer lag is deliberately not here. It comes from {@code MicrometerConsumerListener}, attached to
 * the consumer factory by {@code ListenerContainerFactoryBuilder}, which publishes the Kafka client's own
 * metrics rather than a number this module computed.
 */
@AutoConfiguration(before = MessagingAutoConfiguration.class)
@ConditionalOnClass({KafkaListener.class, MeterRegistry.class})
@ConditionalOnBean(MeterRegistry.class)
@ConditionalOnProperty(prefix = MessagingProperties.PREFIX, name = {"enabled", "metrics.enabled"},
        matchIfMissing = true)
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingMetricsAutoConfiguration {

    /**
     * The Micrometer binding.
     *
     * @param registry the meter registry
     * @return the metrics
     */
    @Bean
    @ConditionalOnMissingBean(MessagingMetrics.class)
    public MessagingMetrics ludwigMessagingMetrics(MeterRegistry registry) {
        return new MicrometerMessagingMetrics(registry);
    }
}
