package ru.ludwigandreas.outbox.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import ru.ludwigandreas.messaging.cluster.KafkaClusterProducers;
import ru.ludwigandreas.messaging.config.KafkaClustersAutoConfiguration;
import ru.ludwigandreas.messaging.settings.KafkaClusters;
import ru.ludwigandreas.outbox.dispatch.OutboxDispatcher;
import ru.ludwigandreas.outbox.dispatch.OutboxDispatcherGroup;
import ru.ludwigandreas.outbox.dispatch.kafka.KafkaOutboxDispatcher;

/**
 * One Kafka transport per cluster: {@code KAFKA} for {@code spring.kafka.*}, and {@code KAFKA:<name>} for
 * every cluster declared under {@code ludwig.kafka.clusters}.
 *
 * <p>Builds its own dedicated {@code KafkaTemplate<String, String>} per cluster (rather than depending on
 * the consumer's own {@code KafkaTemplate} bean, whose generic type Spring Boot defaults to
 * {@code <Object, Object>}) so String key/value serialization is guaranteed regardless of how the
 * consuming application configures its own Kafka producer. Every template's producer comes from
 * messaging's {@link KafkaClusterProducers}, the one seam something standing in for a cluster replaces.
 */
@AutoConfiguration(after = KafkaClustersAutoConfiguration.class)
@ConditionalOnClass(KafkaTemplate.class)
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxKafkaAutoConfiguration {

    /** The prefix of a route transport naming a cluster: {@code kafka:<cluster>}. */
    static final String CLUSTER_TRANSPORT_PREFIX = KafkaOutboxDispatcher.TRANSPORT + ":";

    @Bean
    @ConditionalOnMissingBean(name = "outboxKafkaTemplate")
    public KafkaTemplate<String, String> outboxKafkaTemplate(KafkaClusterProducers producers) {
        return template(producers, KafkaClusters.DEFAULT);
    }

    @Bean
    @ConditionalOnMissingBean(name = "kafkaOutboxDispatcher")
    public OutboxDispatcher kafkaOutboxDispatcher(
            @Qualifier("outboxKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
            OutboxProperties properties) {
        return new KafkaOutboxDispatcher(kafkaTemplate, properties.getKafka().getSendTimeout());
    }

    /**
     * {@code KAFKA:<name>} for every declared cluster other than the default one.
     *
     * @param clusters   the declared clusters
     * @param producers  the producer seam
     * @param properties the outbox configuration, for the send timeout
     * @return the group
     */
    @Bean
    @ConditionalOnMissingBean(name = "kafkaClusterOutboxDispatchers")
    public OutboxDispatcherGroup kafkaClusterOutboxDispatchers(KafkaClusters clusters,
                                                               KafkaClusterProducers producers,
                                                               OutboxProperties properties) {
        List<OutboxDispatcher> dispatchers = new ArrayList<>();
        for (String cluster : clusters.names()) {
            if (!KafkaClusters.DEFAULT.equals(cluster)) {
                String transport = CLUSTER_TRANSPORT_PREFIX + cluster.toUpperCase(Locale.ROOT);
                dispatchers.add(new KafkaOutboxDispatcher(transport, template(producers, cluster),
                        properties.getKafka().getSendTimeout()));
            }
        }
        List<OutboxDispatcher> fixed = List.copyOf(dispatchers);
        return () -> fixed;
    }

    /**
     * Refuses a route whose transport names a cluster nobody declared, at startup rather than as a
     * {@code FAILED} row on the first publish.
     *
     * @param properties the outbox configuration
     * @param clusters   the declared clusters
     * @return the validator
     */
    @Bean
    public InitializingBean outboxKafkaRouteValidator(OutboxProperties properties, KafkaClusters clusters) {
        return () -> {
            properties.getRoutes().forEach((name, route) -> checkRoute(name, route, clusters));
            if (properties.getDefaultRoute() != null) {
                checkRoute("default-route", properties.getDefaultRoute(), clusters);
            }
        };
    }

    private static void checkRoute(String name, OutboxProperties.Route route, KafkaClusters clusters) {
        String transport = route.getTransport();
        if (transport == null || !transport.toUpperCase(Locale.ROOT).startsWith(CLUSTER_TRANSPORT_PREFIX)) {
            return;
        }
        String cluster = transport.substring(CLUSTER_TRANSPORT_PREFIX.length()).toLowerCase(Locale.ROOT);
        if (!clusters.contains(cluster) || KafkaClusters.DEFAULT.equals(cluster)) {
            throw new IllegalStateException("Outbox route '" + name + "' uses transport '" + transport
                    + "', but no Kafka cluster '" + cluster + "' is declared under " + KafkaClusters.PREFIX
                    + ". Use 'kafka' for spring.kafka.*. Declared: " + clusters.names());
        }
    }

    private static KafkaTemplate<String, String> template(KafkaClusterProducers producers, String cluster) {
        return new KafkaTemplate<>(producers.create(cluster, new StringSerializer(), new StringSerializer()));
    }
}
