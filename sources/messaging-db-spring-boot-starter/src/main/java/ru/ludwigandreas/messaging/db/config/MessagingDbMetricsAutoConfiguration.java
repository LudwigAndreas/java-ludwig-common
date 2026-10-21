package ru.ludwigandreas.messaging.db.config;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import ru.ludwigandreas.messaging.db.log.LogStore;
import ru.ludwigandreas.messaging.db.ops.LogLagMonitor;

/** The log-lag gauge, when the application has a meter registry. */
@AutoConfiguration(after = MessagingDbAutoConfiguration.class,
        afterName = "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration")
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean({MeterRegistry.class, LogStore.class})
public class MessagingDbMetricsAutoConfiguration {

    private static final Duration REFRESH = Duration.ofSeconds(30);

    /**
     * The lag gauge.
     *
     * @param store     the log
     * @param registry  the meter registry
     * @param scheduler this module's scheduler
     * @return the monitor
     */
    @Bean
    public LogLagMonitor ludwigMessagingDbLagMonitor(LogStore store, MeterRegistry registry,
                                                    @Qualifier("messagingDbTaskScheduler") TaskScheduler scheduler) {
        return new LogLagMonitor(store, registry, scheduler, REFRESH);
    }
}
