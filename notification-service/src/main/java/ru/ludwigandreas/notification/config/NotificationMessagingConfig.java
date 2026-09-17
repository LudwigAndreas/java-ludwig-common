package ru.ludwigandreas.notification.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.ExponentialBackOff;
import ru.ludwigandreas.notification.messaging.NotificationRequestMessage;
import ru.ludwigandreas.notification.settings.NotificationProperties;

/**
 * The consumer behind the primary ingress.
 *
 * <p>Declared rather than left to Boot's defaults, because three of its settings are load-bearing and
 * none of them is the default.
 *
 * <h2>Manual acknowledgement</h2>
 *
 * <p>{@code RECORD} ack mode commits after the listener returns normally, so a record whose handling
 * threw is redelivered rather than silently marked done. With the default auto-commit, a database
 * outage would advance the offset past every notification that arrived during it and those requests
 * would be gone - the failure mode that looks like "notifications from Tuesday afternoon never
 * arrived" and has no trace anywhere.
 *
 * <h2>Error handling that gives up</h2>
 *
 * <p>Retries with backoff, then dead-letters. Retrying forever is the other way to lose a topic: one
 * permanently unprocessable record blocks its partition, and every producer behind it stops. The
 * dead-letter topic keeps the record for inspection and lets the partition move on.
 *
 * <h2>Deserialization failures are not poison</h2>
 *
 * <p>{@link ErrorHandlingDeserializer} turns a malformed payload into a record the error handler can
 * dead-letter. Without it, a deserialization exception happens before the listener is reached and the
 * container retries the same unparseable bytes forever - the classic poison-pill stall.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ludwig.notification.ingress", name = "kafka-enabled",
        matchIfMissing = true)
public class NotificationMessagingConfig {

    /** Suffix appended to the request topic for records that could not be processed. */
    private static final String DEAD_LETTER_SUFFIX = ".dlt";

    /** Attempts before a record is dead-lettered, including the first. */
    private static final int MAX_ATTEMPTS = 4;

    private static final long INITIAL_BACKOFF_MS = 1_000L;
    private static final double BACKOFF_MULTIPLIER = 3.0;
    private static final long MAX_BACKOFF_MS = 30_000L;

    @Bean
    public ConsumerFactory<String, NotificationRequestMessage> notificationRequestConsumerFactory(
            KafkaProperties kafkaProperties, ObjectMapper objectMapper) {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        // Offsets are committed by the container after the listener returns, never by the broker on a
        // timer - see the class comment.
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        JsonDeserializer<NotificationRequestMessage> payload =
                new JsonDeserializer<>(NotificationRequestMessage.class, objectMapper, false);
        // The type is fixed by this consumer rather than taken from a record header: a header-driven
        // deserializer will instantiate whatever class a producer names, which is a deserialization
        // gadget waiting to happen.
        payload.setUseTypeHeaders(false);

        return new DefaultKafkaConsumerFactory<>(config,
                new ErrorHandlingDeserializer<>(new StringDeserializer()),
                new ErrorHandlingDeserializer<>(payload));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, NotificationRequestMessage>
            notificationRequestListenerContainerFactory(
                    ConsumerFactory<String, NotificationRequestMessage> consumerFactory,
                    org.springframework.kafka.core.KafkaTemplate<Object, Object> kafkaTemplate,
                    NotificationProperties properties) {
        ConcurrentKafkaListenerContainerFactory<String, NotificationRequestMessage> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(errorHandler(kafkaTemplate, properties));
        return factory;
    }

    /**
     * Retry with exponential backoff, then dead-letter.
     *
     * <p>The backoff is in the <em>container</em> rather than in the listener, so a failing record
     * does not occupy a thread while it waits - and so the whole partition pauses, which is what keeps
     * a retried record in order relative to the ones behind it.
     */
    private DefaultErrorHandler errorHandler(
            org.springframework.kafka.core.KafkaTemplate<Object, Object> kafkaTemplate,
            NotificationProperties properties) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new org.apache.kafka.common.TopicPartition(
                        properties.getIngress().getTopic() + DEAD_LETTER_SUFFIX,
                        // -1 lets the broker choose the partition. Preserving the source partition
                        // would require the dead-letter topic to have at least as many, which nothing
                        // enforces - and a send to a partition that does not exist fails silently.
                        -1));

        ExponentialBackOff backOff = new ExponentialBackOff(INITIAL_BACKOFF_MS, BACKOFF_MULTIPLIER);
        backOff.setMaxInterval(MAX_BACKOFF_MS);
        backOff.setMaxAttempts(MAX_ATTEMPTS);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // A payload that could not be deserialized will never deserialize, so it goes straight to the
        // dead-letter topic instead of spending four attempts proving it.
        handler.addNotRetryableExceptions(
                org.springframework.kafka.support.serializer.DeserializationException.class,
                org.springframework.messaging.converter.MessageConversionException.class);
        return handler;
    }
}
