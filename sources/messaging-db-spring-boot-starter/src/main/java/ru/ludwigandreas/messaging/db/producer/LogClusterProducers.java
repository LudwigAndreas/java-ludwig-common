package ru.ludwigandreas.messaging.db.producer;

import java.util.Set;
import org.apache.kafka.common.serialization.Serializer;
import org.springframework.kafka.core.ProducerFactory;
import ru.ludwigandreas.messaging.cluster.KafkaClusterProducers;
import ru.ludwigandreas.messaging.db.log.LogStore;

/**
 * Messaging's producer seam with the replaced clusters behind the log.
 *
 * <p>Every platform producer comes from {@link KafkaClusterProducers} - the outbox's templates and every
 * dead-letter template - so standing in for a cluster here catches every write to it, and the outbox and
 * messaging stay unchanged. Every other cluster gets the producer it would have had.
 */
public class LogClusterProducers implements KafkaClusterProducers {

    private final KafkaClusterProducers delegate;
    private final LogStore store;
    private final Set<String> replaced;

    /**
     * Creates the seam.
     *
     * @param delegate the producers every non-replaced cluster gets
     * @param store    the log
     * @param replaced the clusters the log stands in for; empty while draining, so every write goes to its
     *                 cluster's own address
     */
    public LogClusterProducers(KafkaClusterProducers delegate, LogStore store, Set<String> replaced) {
        this.delegate = delegate;
        this.store = store;
        this.replaced = Set.copyOf(replaced);
    }

    @Override
    public <K, V> ProducerFactory<K, V> create(String cluster, Serializer<K> keySerializer,
                                               Serializer<V> valueSerializer) {
        return replaced.contains(cluster)
                ? new LogProducerFactory<>(store, keySerializer, valueSerializer)
                : delegate.create(cluster, keySerializer, valueSerializer);
    }
}
