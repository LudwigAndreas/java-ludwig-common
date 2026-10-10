package ru.ludwigandreas.notification.local;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import ru.ludwigandreas.notification.messaging.NotificationRequestMessage;
import ru.ludwigandreas.testsupport.local.fixture.KafkaDeliveries;
import ru.ludwigandreas.testsupport.local.fixture.SeedFixture;

/**
 * One notification request on the Kafka ingress topic, so the consumer has something to consume.
 *
 * <p>It is published the way a peer service publishes it: a JSON document keyed by its idempotency
 * key, through a plain string producer. The service's own listener then does everything else.
 */
public class IngressSeed implements SeedFixture {

    /** The idempotency key, and record key, of the seeded request. */
    public static final String IDEMPOTENCY_KEY = "local-seed-ingress";

    private final KafkaProperties kafkaProperties;
    private final Environment environment;
    private final ObjectMapper objectMapper;

    public IngressSeed(KafkaProperties kafkaProperties, Environment environment, ObjectMapper objectMapper) {
        this.kafkaProperties = kafkaProperties;
        this.environment = environment;
        this.objectMapper = objectMapper;
    }

    /**
     * A producer of string records for the broker the service is connected to.
     *
     * @param kafkaProperties the service's Kafka configuration
     * @return a template that serializes key and value as strings
     */
    public static KafkaTemplate<String, String> stringTemplate(KafkaProperties kafkaProperties) {
        Map<String, Object> config = kafkaProperties.buildProducerProperties(null);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
                config, new StringSerializer(), new StringSerializer()));
    }

    /**
     * A request for one user by email, as the ingress topic carries it.
     *
     * @param objectMapper   the service's mapper
     * @param idempotencyKey the request's idempotency key
     * @param userId         the recipient
     * @return the JSON payload
     */
    public static String welcomeFor(ObjectMapper objectMapper, String idempotencyKey, String userId) {
        NotificationRequestMessage message = new NotificationRequestMessage(
                idempotencyKey, "welcome", "account", "TRANSACTIONAL", "NORMAL", Set.of("EMAIL"),
                List.of(new NotificationRequestMessage.RecipientMessage(userId, null, "en", null, Map.of())),
                Map.of("productName", "Ludwig"), null);
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize an ingress request", ex);
        }
    }

    @Override
    public void seed() {
        String topic = environment.getRequiredProperty("ludwig.notification.ingress.topic");
        KafkaDeliveries.publish(stringTemplate(kafkaProperties), List.of(new ProducerRecord<>(
                topic, IDEMPOTENCY_KEY, welcomeFor(objectMapper, IDEMPOTENCY_KEY, RecipientSeed.EMAIL_ONLY))));
    }

    @Override
    public boolean needsKafka() {
        return true;
    }

    @Override
    public List<Class<? extends SeedFixture>> after() {
        return List.of(RecipientSeed.class);
    }
}
