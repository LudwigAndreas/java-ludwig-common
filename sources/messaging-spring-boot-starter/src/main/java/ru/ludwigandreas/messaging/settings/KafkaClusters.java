package ru.ludwigandreas.messaging.settings;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;

/**
 * The Kafka clusters a service talks to, by name.
 *
 * <p>{@code spring.kafka.*} is the cluster named {@value #DEFAULT}, exactly as before named clusters
 * existed. A named cluster is declared under {@code ludwig.kafka.clusters.<name>} with the same property
 * shape, and is bound <em>on top of</em> {@code spring.kafka.*}: an entry says only what differs - an
 * address, a security block, a client id - and inherits serializers, group defaults and everything else.
 * A narrower record of "the fields a cluster usually differs in" was the alternative, and every field it
 * left out would have become a feature request the first time a partner used a different SASL mechanism.
 */
public final class KafkaClusters {

    /** The name of the cluster {@code spring.kafka.*} describes. */
    public static final String DEFAULT = "default";

    /** Where named clusters are declared. */
    public static final String PREFIX = "ludwig.kafka.clusters";

    /**
     * Lower-case letters, digits and dashes, short enough that {@code kafka:<name>} fits the outbox's
     * {@code transport} column ({@code VARCHAR(50)}).
     */
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,43}");

    private final Map<String, KafkaProperties> clusters;

    private KafkaClusters(Map<String, KafkaProperties> clusters) {
        this.clusters = Collections.unmodifiableMap(clusters);
    }

    /**
     * Binds every declared cluster.
     *
     * @param binder   the environment's binder
     * @param defaults Boot's own {@code KafkaProperties} bean, which is the {@value #DEFAULT} cluster
     * @return the clusters
     */
    public static KafkaClusters bind(Binder binder, KafkaProperties defaults) {
        Map<String, KafkaProperties> clusters = new LinkedHashMap<>();
        clusters.put(DEFAULT, defaults);
        Set<String> names = binder.bind(PREFIX, Bindable.mapOf(String.class, Object.class))
                .map(Map::keySet).orElse(Set.of());
        for (String name : names) {
            if (DEFAULT.equals(name)) {
                throw new IllegalStateException(PREFIX + "." + DEFAULT + " is declared, but spring.kafka.* already"
                        + " is the '" + DEFAULT + "' cluster; configure it there.");
            }
            if (!NAME.matcher(name).matches()) {
                throw new IllegalStateException("Kafka cluster name '" + name + "' must be lower-case letters,"
                        + " digits and dashes, at most 44 characters, so that 'kafka:" + name
                        + "' fits an outbox transport.");
            }
            KafkaProperties properties = binder.bind("spring.kafka", KafkaProperties.class)
                    .orElseGet(KafkaProperties::new);
            binder.bind(PREFIX + "." + name, Bindable.ofInstance(properties));
            clusters.put(name, properties);
        }
        return new KafkaClusters(clusters);
    }

    /**
     * The declared cluster names, {@value #DEFAULT} first.
     *
     * @return the names
     */
    public Set<String> names() {
        return clusters.keySet();
    }

    /**
     * Whether a cluster is declared.
     *
     * @param name the name
     * @return {@code true} for {@value #DEFAULT} and every declared name
     */
    public boolean contains(String name) {
        return clusters.containsKey(name);
    }

    /**
     * A cluster's Kafka properties.
     *
     * @param name the cluster
     * @return its properties, inheritance applied
     * @throws IllegalStateException naming the cluster when it is not declared
     */
    public KafkaProperties properties(String name) {
        KafkaProperties properties = clusters.get(name);
        if (properties == null) {
            throw new IllegalStateException("No Kafka cluster named '" + name + "'. Declare it under " + PREFIX
                    + "." + name + ", or use '" + DEFAULT + "' for spring.kafka.*. Declared: " + names());
        }
        return properties;
    }
}
