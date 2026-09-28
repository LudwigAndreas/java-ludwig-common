package ru.ludwigandreas.idempotency.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.listener.adapter.RecordFilterStrategy;
import ru.ludwigandreas.idempotency.api.IdempotencyStore;
import ru.ludwigandreas.idempotency.api.RequestFingerprint;
import ru.ludwigandreas.idempotency.kafka.IdempotentRecordFilterStrategy;
import ru.ludwigandreas.idempotency.metrics.IdempotencyMetrics;

/**
 * The consumer surface, when spring-kafka is present and a deployment asked for it.
 *
 * <p>Registering the bean is not the same as switching dedup on, and that is deliberate. A
 * {@link RecordFilterStrategy} deduplicates only the listener containers whose factory was given it, so a
 * service adopts it one factory at a time - which is what lets a service dedup its command listener while
 * leaving its projection listener alone. A projection that converges on a value is already correct on a
 * replay; putting a claim in front of it would add a table, a write and a failure mode for a guarantee it
 * already had.
 */
@AutoConfiguration
@ConditionalOnClass(RecordFilterStrategy.class)
@ConditionalOnProperty(prefix = "ludwig.idempotency.kafka", name = "enabled", havingValue = "true")
@AutoConfigureAfter(IdempotencyPersistenceAutoConfiguration.class)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyKafkaAutoConfiguration {

    /**
     * The record filter.
     *
     * @param store        the claim store
     * @param fingerprints hashes the record's value
     * @param properties   the configuration
     * @param metrics      what this module reports about itself
     * @return the strategy, to be handed to a listener container factory
     */
    @Bean
    @ConditionalOnMissingBean(IdempotentRecordFilterStrategy.class)
    @ConditionalOnBean(IdempotencyStore.class)
    public IdempotentRecordFilterStrategy idempotentRecordFilterStrategy(
            IdempotencyStore store, RequestFingerprint fingerprints, IdempotencyProperties properties,
            IdempotencyMetrics metrics) {
        return new IdempotentRecordFilterStrategy(store, fingerprints, properties, metrics);
    }
}
