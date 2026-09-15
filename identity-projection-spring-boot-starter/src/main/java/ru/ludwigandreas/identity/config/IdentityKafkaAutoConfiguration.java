package ru.ludwigandreas.identity.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import ru.ludwigandreas.identity.kafka.OidcUserEventListener;
import ru.ludwigandreas.identity.projection.IdentityProjectionService;

/**
 * Registers the Kafka consumer, only when spring-kafka is present. A service can use the projection
 * tables without consuming the topic at all - an admin service reading the same schema, or a test.
 */
@AutoConfiguration(after = IdentityProjectionAutoConfiguration.class)
@ConditionalOnClass(KafkaListener.class)
@ConditionalOnProperty(prefix = "ludwig.identity.kafka", name = "enabled", matchIfMissing = true)
public class IdentityKafkaAutoConfiguration {

    /**
     * Falls back to a private {@link ObjectMapper} rather than failing when the application has none.
     * {@link JavaTimeModule} is registered explicitly because {@code occurredAt} is an {@link
     * java.time.Instant} and the ordering guard in the projection depends on it deserializing correctly -
     * without the module it would fail, and an event that fails to parse is dropped.
     */
    @Bean
    @ConditionalOnMissingBean(OidcUserEventListener.class)
    public OidcUserEventListener oidcUserEventListener(IdentityProjectionService projectionService,
                                                        ObjectProvider<ObjectMapper> objectMapper) {
        return new OidcUserEventListener(projectionService,
                objectMapper.getIfAvailable(IdentityKafkaAutoConfiguration::defaultObjectMapper));
    }

    private static ObjectMapper defaultObjectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
