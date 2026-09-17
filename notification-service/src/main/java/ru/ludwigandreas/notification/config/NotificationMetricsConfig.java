package ru.ludwigandreas.notification.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.notification.service.metrics.MicrometerNotificationMetrics;
import ru.ludwigandreas.notification.service.metrics.NoopNotificationMetrics;
import ru.ludwigandreas.notification.service.metrics.NotificationMetrics;

/**
 * Metrics wiring, matching the pattern every other module in this repository uses: a Micrometer
 * implementation when a registry is present, and a no-op fallback that is always registered.
 *
 * <p>The fallback is what lets every call site record unconditionally. A nullable metrics
 * collaborator would put an {@code if} in front of every recording, and the one that gets forgotten
 * is a {@code NullPointerException} in the dispatch loop - a metrics bug that stops notifications.
 */
@Configuration(proxyBeanMethods = false)
public class NotificationMetricsConfig {

    @Bean
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnMissingBean(NotificationMetrics.class)
    @ConditionalOnProperty(prefix = "ludwig.notification.metrics", name = "enabled",
            matchIfMissing = true)
    public NotificationMetrics micrometerNotificationMetrics(MeterRegistry registry) {
        return new MicrometerNotificationMetrics(registry);
    }

    @Bean
    @ConditionalOnMissingBean(NotificationMetrics.class)
    public NotificationMetrics noopNotificationMetrics() {
        return new NoopNotificationMetrics();
    }
}
