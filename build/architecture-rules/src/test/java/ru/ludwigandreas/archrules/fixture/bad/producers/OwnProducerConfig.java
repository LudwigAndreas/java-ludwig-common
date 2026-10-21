package ru.ludwigandreas.archrules.fixture.bad.producers;

import java.util.Map;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;

/** Builds a producer of its own, bypassing the cluster seam. */
public class OwnProducerConfig {

    /**
     * The factory.
     *
     * @return a factory nobody can stand in for
     */
    public DefaultKafkaProducerFactory<String, String> producerFactory() {
        return new DefaultKafkaProducerFactory<>(Map.of());
    }
}
