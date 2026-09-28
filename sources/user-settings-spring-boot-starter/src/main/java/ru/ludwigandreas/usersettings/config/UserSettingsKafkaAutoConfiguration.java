package ru.ludwigandreas.usersettings.config;

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
import ru.ludwigandreas.messaging.api.EnvelopeReader;
import ru.ludwigandreas.messaging.consumer.ListenerContainerFactoryBuilder;
import ru.ludwigandreas.usersettings.kafka.SettingsEventListener;
import ru.ludwigandreas.usersettings.projection.SettingsProjectionService;

/**
 * The projection's Kafka consumer, registered only when spring-kafka is present.
 *
 * <p>Separate from {@code UserSettingsProjectionAutoConfiguration} because a service can use the
 * replica's tables without consuming the topic: an admin service reading the same schema, a test
 * that feeds the projection service directly, a replica being rebuilt from a database restore.
 *
 * <h2>What changed when this moved onto the platform's container factory</h2>
 *
 * <p>This module's own factory had a defect that loses nothing and delivers nothing: {@code AckMode.MANUAL}
 * with <b>no error handler at all</b>. The listener acknowledged a record it had applied or deliberately
 * dropped and rethrew a failure without acknowledging, which was the right shape - and with no error
 * handler behind it, the container redelivered that record forever, with no backoff, at full speed. A
 * projection that could not apply a change because a constraint fired did not fall behind; it stopped, hot,
 * and stayed stopped. There was no dead-letter topic to give up into and nothing counted the attempts.
 *
 * <p>The factory now comes from {@code messaging-spring-boot-starter} and this module gains, for the first
 * time, retries with exponential backoff and a dead-letter topic - which means the dead-letter topic for the
 * settings change stream has to be provisioned before this ships. See this module's README.
 */
@AutoConfiguration(after = UserSettingsProjectionAutoConfiguration.class)
// Conditional on the platform's consumer wiring as well as on spring-kafka: this module no longer declares
// a container factory, an error handler or a dead-letter topic of its own, so a classpath with a broker
// client and no messaging starter has no consumer to offer - and failing the condition is how a deployment
// learns that in one line, rather than through a missing-bean message about a factory.
@ConditionalOnClass({KafkaListener.class, ListenerContainerFactoryBuilder.class})
@ConditionalOnProperty(prefix = "ludwig.user-settings.projection", name = "enabled", havingValue = "true")
public class UserSettingsKafkaAutoConfiguration {

    /** The container factory the projection listener runs on; named so the listener can ask for it. */
    public static final String CONTAINER_FACTORY = "userSettingsKafkaListenerContainerFactory";

    /** The name this consumer's settings live under in {@code ludwig.messaging.consumers.*}. */
    public static final String CONSUMER_NAME = "user-settings-projection";

    /**
     * The projection's container, built at the exact type its listener takes.
     *
     * <p>The previous version of this bean took a {@code ConsumerFactory<?, ?>} and cast it, with a comment
     * explaining that asking for {@code ConsumerFactory<String, String>} "looks tidier and does not work"
     * because a service declaring its own differently-typed factory leaves no bean matching that signature.
     * The diagnosis was right and the cast was the wrong remedy - it removed the compiler's check rather
     * than satisfying it. Nothing is injected here now: the factory is <em>constructed</em> at
     * {@code <String, String>} by the platform builder, so there is no shared signature to collide with and
     * nothing to cast. The comment is not relocated, it is obsolete.
     *
     * <p>{@code AckMode.RECORD} now, from the platform default, rather than {@code MANUAL}.
     * {@code SettingsEventListener} is unaffected: it takes {@code Acknowledgment} as an optional parameter
     * and null-checks it, and every path through it either applies the event, deliberately drops it, or
     * rethrows - so committing after a normal return commits exactly the records it acknowledged before, and
     * a rethrow still leaves the offset uncommitted. What is new is that the rethrow now reaches an error
     * handler.
     *
     * @param builder the platform's container factory builder
     * @param properties this module's configuration, for the topic the silence signal watches
     * @return the factory
     */
    @Bean(name = CONTAINER_FACTORY)
    @ConditionalOnMissingBean(name = CONTAINER_FACTORY)
    @ConditionalOnBean(ListenerContainerFactoryBuilder.class)
    public ConcurrentKafkaListenerContainerFactory<String, String> userSettingsKafkaListenerContainerFactory(
            ListenerContainerFactoryBuilder builder, UserSettingsProperties properties) {
        // A text payload rather than a typed one, deliberately and unchanged: the listener dispatches on
        // the event-type header across three payload types on one topic, which a container factory fixed to
        // a single type cannot express. The shared error handling, dead-letter topic and version gate are
        // identical either way.
        return builder.forTextPayload(CONSUMER_NAME, properties.getProjection().getTopic());
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
                                                        ObjectProvider<ObjectMapper> objectMapper,
                                                        EnvelopeReader envelopeReader) {
        return new SettingsEventListener(projectionService,
                objectMapper.getIfAvailable(ObjectMapper::new).copy().registerModule(new JavaTimeModule()),
                envelopeReader);
    }
}
