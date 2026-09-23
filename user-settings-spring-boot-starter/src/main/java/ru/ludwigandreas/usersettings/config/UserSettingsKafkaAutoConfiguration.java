package ru.ludwigandreas.usersettings.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import ru.ludwigandreas.usersettings.kafka.SettingsEventListener;
import ru.ludwigandreas.usersettings.projection.SettingsProjectionService;

/**
 * The projection's Kafka consumer, registered only when spring-kafka is present.
 *
 * <p>Separate from {@code UserSettingsProjectionAutoConfiguration} because a service can use the
 * replica's tables without consuming the topic: an admin service reading the same schema, a test
 * that feeds the projection service directly, a replica being rebuilt from a database restore.
 */
@AutoConfiguration(after = UserSettingsProjectionAutoConfiguration.class)
@ConditionalOnClass(KafkaListener.class)
@ConditionalOnProperty(prefix = "ludwig.user-settings.projection", name = "enabled", havingValue = "true")
public class UserSettingsKafkaAutoConfiguration {

    /** The container factory the projection listener runs on; named so the listener can ask for it. */
    public static final String CONTAINER_FACTORY = "userSettingsKafkaListenerContainerFactory";

    /**
     * A container of this module's own, with manual acknowledgement.
     *
     * <p>The module does not use the application's default container factory, and the reason is the
     * projection's whole failure contract. The listener acknowledges a record it has applied or
     * deliberately dropped, and does <em>not</em> acknowledge one whose application failed - a
     * database outage, a constraint - so the container redelivers it and the replica catches up
     * rather than silently skipping a change. With the default {@code BATCH} acknowledgement the
     * container commits the offset regardless, and a failed apply is a change the replica never sees
     * again and has no way to discover.
     *
     * <p>Owning the factory rather than documenting "please set ack-mode to MANUAL" also means a
     * service whose own listeners want batch acknowledgement is unaffected: the two containers have
     * different settings because they have different needs, which is exactly what a per-listener
     * factory is for.
     */
    @Bean(name = CONTAINER_FACTORY)
    @ConditionalOnMissingBean(name = CONTAINER_FACTORY)
    @SuppressWarnings("unchecked")
    public ConcurrentKafkaListenerContainerFactory<String, String> userSettingsKafkaListenerContainerFactory(
            ConsumerFactory<?, ?> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        // Wildcarded, and cast. Asking for a ConsumerFactory<String, String> looks tidier and does not
        // work: a service that declares its own factory with different type parameters - which several
        // do, because their own payloads are not Strings - has no bean matching that signature, and
        // the context fails to start with a message about generics rather than about settings. The
        // deserializers are Spring Kafka's defaults for the consumer, and the listener takes its
        // payload as a String, so the cast holds for every configuration that could deliver here.
        factory.setConsumerFactory((ConsumerFactory<String, String>) consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        return factory;
    }

    /**
     * Falls back to a private {@link ObjectMapper} rather than failing when the application has none.
     * {@link JavaTimeModule} is registered explicitly because {@code occurredAt} is an
     * {@link java.time.Instant} and the ordering guard in the projection depends on it deserializing
     * correctly - without the module it would fail, and an event that fails to parse is dropped.
     */
    @Bean
    @ConditionalOnMissingBean(SettingsEventListener.class)
    public SettingsEventListener settingsEventListener(SettingsProjectionService projectionService,
                                                        ObjectProvider<ObjectMapper> objectMapper) {
        return new SettingsEventListener(projectionService,
                objectMapper.getIfAvailable(ObjectMapper::new).copy().registerModule(new JavaTimeModule()));
    }
}
