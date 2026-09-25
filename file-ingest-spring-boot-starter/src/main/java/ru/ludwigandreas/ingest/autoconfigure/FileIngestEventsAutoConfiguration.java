package ru.ludwigandreas.ingest.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.ingest.event.IngestEventPublisher;
import ru.ludwigandreas.ingest.event.OutboxIngestEventPublisher;
import ru.ludwigandreas.outbox.api.OutboxEventPublisher;

/**
 * Publishes completion through the outbox, when the outbox is present and events are switched on.
 *
 * <p>Separate from {@link FileIngestAutoConfiguration} for the same reason the metrics are: the outbox
 * starter is optional, and a {@code @Bean} method naming {@code OutboxEventPublisher} in a class
 * Spring always introspects would fail to load on a context without it, before any method-level
 * condition was consulted.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(OutboxEventPublisher.class)
@AutoConfigureBefore(FileIngestAutoConfiguration.class)
@ConditionalOnProperty(prefix = "ludwig.ingest.events", name = "enabled")
public class FileIngestEventsAutoConfiguration {

    /**
     * The outbox-backed publisher.
     *
     * @param outbox the platform's outbox
     * @return the publisher
     */
    @Bean
    @ConditionalOnBean(OutboxEventPublisher.class)
    @ConditionalOnMissingBean(IngestEventPublisher.class)
    public IngestEventPublisher outboxIngestEventPublisher(OutboxEventPublisher outbox) {
        return new OutboxIngestEventPublisher(outbox);
    }
}
