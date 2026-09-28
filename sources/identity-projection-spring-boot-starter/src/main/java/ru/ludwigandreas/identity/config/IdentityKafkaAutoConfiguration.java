package ru.ludwigandreas.identity.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import ru.ludwigandreas.identity.kafka.OidcUserEventListener;
import ru.ludwigandreas.identity.projection.IdentityProjectionService;
import ru.ludwigandreas.messaging.consumer.ListenerContainerFactoryBuilder;

/**
 * Registers the Kafka consumer, only when spring-kafka is present. A service can use the projection
 * tables without consuming the topic at all - an admin service reading the same schema, or a test.
 *
 * <h2>This module had no container factory at all, and that was the bug</h2>
 *
 * <p>{@code OidcUserEventListener} named no {@code containerFactory}, so it ran on Boot's auto-configured
 * one with Boot's default {@code DefaultErrorHandler}: ten attempts, <b>no backoff</b>, and then log the
 * failure and move on. The record was dropped. The only trace was a log line in a service nobody tails, and
 * the projection was silently missing one person's directory data - which presents, weeks later, as a
 * notification that went to the wrong address.
 *
 * <p>Boot's default is a reasonable default for an application that has thought about nothing. It is not a
 * reasonable default for a projection, and the module never chose it - it inherited it by declaring nothing.
 *
 * <p>The factory now comes from {@code messaging-spring-boot-starter}: four attempts with growing gaps, and
 * then a dead-letter topic, which means the dead-letter topic for the OIDC user stream has to be provisioned
 * before this ships. See this module's README.
 */
@AutoConfiguration(after = IdentityProjectionAutoConfiguration.class)
@ConditionalOnClass(KafkaListener.class)
@ConditionalOnProperty(prefix = "ludwig.identity.kafka", name = "enabled", matchIfMissing = true)
public class IdentityKafkaAutoConfiguration {

    /** The container factory the projection listener runs on; named so the listener can ask for it. */
    public static final String CONTAINER_FACTORY = "identityKafkaListenerContainerFactory";

    /** The name this consumer's settings live under in {@code ludwig.messaging.consumers.*}. */
    public static final String CONSUMER_NAME = "identity-projection";

    /**
     * The projection's container.
     *
     * <p>A text payload, because {@link OidcUserEventListener} parses its own JSON. Moving that listener onto
     * the typed inbound envelope is a change worth making and deliberately not made here: it alters what the
     * listener does with a malformed payload, and bundling a behaviour change into the fix for a silent drop
     * would make the fix unreviewable. The error handling and the dead-letter topic are identical either way,
     * which is the part that was urgent.
     *
     * @param builder    the platform's container factory builder
     * @param properties this module's configuration, for the topic the silence signal watches
     * @return the factory
     */
    @Bean(name = CONTAINER_FACTORY)
    @ConditionalOnMissingBean(name = CONTAINER_FACTORY)
    @ConditionalOnBean(ListenerContainerFactoryBuilder.class)
    public ConcurrentKafkaListenerContainerFactory<String, String> identityKafkaListenerContainerFactory(
            ListenerContainerFactoryBuilder builder, IdentityProjectionProperties properties) {
        return builder.forTextPayload(CONSUMER_NAME, properties.getKafka().getTopic());
    }

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
