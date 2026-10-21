package ru.ludwigandreas.messaging.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.KafkaTemplate;
import ru.ludwigandreas.messaging.cluster.DefaultKafkaClusterProducers;
import ru.ludwigandreas.messaging.cluster.KafkaClusterProducers;
import ru.ludwigandreas.messaging.settings.KafkaClusters;

/**
 * The named Kafka clusters and the producer seam.
 *
 * <p>Separate from {@link MessagingAutoConfiguration} and not gated by {@code ludwig.messaging.enabled},
 * because the outbox produces through these too and must not lose its clusters when a service switches
 * this module's consumer wiring off.
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@ConditionalOnClass(KafkaTemplate.class)
public class KafkaClustersAutoConfiguration {

    /**
     * The declared clusters, {@code spring.kafka.*} as {@code default}.
     *
     * @param environment where {@code ludwig.kafka.clusters.*} is bound from
     * @param defaults    Boot's Kafka configuration
     * @return the clusters
     */
    @Bean
    @ConditionalOnMissingBean(KafkaClusters.class)
    public KafkaClusters ludwigKafkaClusters(Environment environment, KafkaProperties defaults) {
        return KafkaClusters.bind(Binder.get(environment), defaults);
    }

    /**
     * Every platform producer's origin.
     *
     * @param clusters the declared clusters
     * @return the producer seam
     */
    @Bean
    @ConditionalOnMissingBean(KafkaClusterProducers.class)
    public KafkaClusterProducers ludwigKafkaClusterProducers(KafkaClusters clusters) {
        return new DefaultKafkaClusterProducers(clusters);
    }
}
