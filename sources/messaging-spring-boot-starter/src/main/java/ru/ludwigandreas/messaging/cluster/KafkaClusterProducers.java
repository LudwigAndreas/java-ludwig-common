package ru.ludwigandreas.messaging.cluster;

import org.apache.kafka.common.serialization.Serializer;
import org.springframework.kafka.core.ProducerFactory;

/**
 * The one place a platform producer is built: the outbox's templates and every dead-letter template.
 *
 * <p>One seam rather than a factory per caller, so that something standing in for a cluster - the DB
 * log, until a cluster exists - replaces one bean and catches every write to that cluster, outbox and
 * dead letters alike. The {@code kafka.producers-come-from-the-cluster-seam} architecture rule keeps it
 * the only place.
 */
public interface KafkaClusterProducers {

    /**
     * A producer factory for a cluster.
     *
     * @param cluster         the cluster's name
     * @param keySerializer   the key serializer
     * @param valueSerializer the value serializer
     * @param <K>             the key type
     * @param <V>             the value type
     * @return the factory
     * @throws IllegalStateException naming the cluster when it is not declared
     */
    <K, V> ProducerFactory<K, V> create(String cluster, Serializer<K> keySerializer, Serializer<V> valueSerializer);
}
