package ru.ludwigandreas.notification.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import ru.ludwigandreas.messaging.consumer.ListenerContainerFactoryBuilder;
import ru.ludwigandreas.notification.messaging.NotificationRequestMessage;
import ru.ludwigandreas.notification.settings.NotificationProperties;

/**
 * The consumer behind the primary ingress.
 *
 * <p>Everything this class used to decide - the acknowledgement mode, the error handler, the backoff, the
 * non-retryable exceptions, the dead-letter destination, the deserializers - is now
 * {@code messaging-spring-boot-starter}'s, and its reasoning moved there with it. This configuration had
 * been the only one of the platform's three Kafka consumers that was thought through, which is exactly why
 * it became the shared default rather than being replaced by one.
 *
 * <p>What remains here is the two things that are genuinely this service's: the payload type, and the
 * topic. Both are named at the one point they have to be named.
 *
 * <h2>The effective configuration is unchanged</h2>
 *
 * <p>Deliberately, and it is worth stating because the point of a consolidation is not to change the one
 * caller that was already right. {@code RECORD} acknowledgement, {@code ErrorHandlingDeserializer} around
 * both key and value, auto-commit off, a {@code JsonDeserializer} fixed to this type with type headers off,
 * four attempts at 1s tripling to a 30s cap, {@code DeserializationException} and
 * {@code MessageConversionException} non-retryable, and a dead-letter topic at {@code <topic>.dlt} on a
 * broker-chosen partition - all the same values, from
 * {@code ResolvedConsumerSettings}' named platform defaults, which were taken from this class.
 *
 * <p>Three things are new, and none of them changes an existing behaviour: the consumer's lag is now bound
 * to Micrometer through {@code MicrometerConsumerListener}, a dead-letter send is now counted and audited,
 * and the correlation interceptor the observability starter contributes is now composed into this factory
 * instead of being bypassed by it.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ludwig.notification.ingress", name = "kafka-enabled",
        matchIfMissing = true)
public class NotificationMessagingConfig {

    /**
     * The name this consumer's settings live under in {@code ludwig.messaging.consumers.*}.
     *
     * <p>Not the bean name. The bean name is what a {@code @KafkaListener} references and is a Spring
     * concern; this is what an operator writes in a configuration file, and keeping them separate means a
     * bean rename is not a silent configuration change.
     */
    public static final String CONSUMER_NAME = "notification-ingress";

    /** The container factory bean name, referenced by {@code NotificationRequestListener}. */
    public static final String CONTAINER_FACTORY = "notificationRequestListenerContainerFactory";

    /**
     * The ingress container factory, at the exact type this service's payload is.
     *
     * <p>Constructed at that type rather than injected, which is what removes the need for the cast the
     * platform's other consumer had: no bean of a shared signature is looked up, so there is nothing for a
     * differently-typed factory to collide with.
     *
     * @param builder    the platform's factory builder
     * @param properties this service's configuration, for the topic the silence signal watches
     * @return the factory
     */
    @Bean(CONTAINER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, NotificationRequestMessage>
            notificationRequestListenerContainerFactory(ListenerContainerFactoryBuilder builder,
                                                        NotificationProperties properties) {
        return builder.forJsonPayload(CONSUMER_NAME, NotificationRequestMessage.class,
                properties.getIngress().getTopic());
    }
}
