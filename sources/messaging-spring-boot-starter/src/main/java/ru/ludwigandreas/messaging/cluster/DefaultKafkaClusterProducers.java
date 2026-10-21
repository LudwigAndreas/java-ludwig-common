package ru.ludwigandreas.messaging.cluster;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.Serializer;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.ProducerFactory;
import ru.ludwigandreas.messaging.settings.KafkaClusters;

/** Kafka producers over each cluster's {@code KafkaProperties}. */
public class DefaultKafkaClusterProducers implements KafkaClusterProducers {

    private final KafkaClusters clusters;

    /**
     * Creates the producers.
     *
     * @param clusters the declared clusters
     */
    public DefaultKafkaClusterProducers(KafkaClusters clusters) {
        this.clusters = clusters;
    }

    @Override
    public <K, V> ProducerFactory<K, V> create(String cluster, Serializer<K> keySerializer,
                                               Serializer<V> valueSerializer) {
        Map<String, Object> config = new HashMap<>(clusters.properties(cluster).buildProducerProperties(null));
        // Removed rather than overridden: the serializer instances are passed to the factory, and a leftover
        // class-name property is what a future reader would believe.
        config.remove(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG);
        config.remove(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);
        return new DefaultKafkaProducerFactory<>(config, keySerializer, valueSerializer);
    }
}
