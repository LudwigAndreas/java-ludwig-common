package ru.ludwigandreas.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import ru.ludwigandreas.notification.repository.DeliveryStatusHistoryRepository;
import ru.ludwigandreas.idempotency.repository.IdempotencyClaimRepository;
import ru.ludwigandreas.notification.repository.NotificationDeliveryRepository;
import ru.ludwigandreas.notification.repository.NotificationRequestRepository;
import ru.ludwigandreas.notification.repository.entity.DeliveryStatus;
import ru.ludwigandreas.notification.repository.entity.NotificationRequestEntity;
import ru.ludwigandreas.notification.repository.entity.NotificationSource;
import ru.ludwigandreas.notification.service.queue.DeliveryDispatchService;

/**
 * The primary ingress, against a real broker: a record on the topic becomes a request and its
 * deliveries.
 *
 * <p>Its own class rather than a case in the lifecycle suite, because a broker is the most expensive
 * thing in this test suite and only a handful of assertions actually need one - everything about
 * fan-out, preferences and dispatch is identical whichever adapter the command arrived through, which
 * is the property the single application service exists to give.
 *
 * <p>What genuinely needs a broker is the part that is <em>not</em> shared: that the consumer is
 * wired at all, that an at-least-once redelivery of the same record does not send twice, and that a
 * record nothing can be done with is acknowledged rather than replayed forever.
 */
@Testcontainers
@SpringBootTest
@Import({TestSecurityConfiguration.class, RecipientFixtures.class})
@TestPropertySource(properties = {
        "ludwig.notification.queue.poller-enabled=false",
        "ludwig.notification.retention.enabled=false",
        "ludwig.identity.kafka.enabled=false",
        // The settings replica is fed by the fixtures, not by a second consumer on the same broker.
        "ludwig.user-settings.projection.enabled=true",
        "ludwig.notification.preferences.declinable-categories=campaigns,marketing",
        "ludwig.outbox.polling.enabled=false",
        "ludwig.notification.receipts.signing-secret=test-receipt-secret",
        // The consumer is the subject of this class, so unlike the other integration tests it is on.
        "ludwig.notification.ingress.kafka-enabled=true",
        "ludwig.notification.ingress.topic=" + KafkaIngressIntegrationTest.TOPIC,
        // One thread, so a redelivery is genuinely sequential and the test is not racing itself.
        "ludwig.notification.ingress.concurrency=1",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        // No SMTP server in this class: dispatch is exercised in the lifecycle suite, and what is
        // asserted here stops at the queue.
        "ludwig.notification.channels.email.enabled=true",
        "spring.mail.host=127.0.0.1",
        "spring.mail.port=3025"
})
class KafkaIngressIntegrationTest {

    static final String TOPIC = "test.notifications.requests";

    private static final String RECIPIENT_ID = "kafka-user";
    private static final String RECIPIENT_EMAIL = "kafka@example.com";

    /** Pinned by name, version and digest - the same rule every other container here follows. */
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("postgres:16-alpine@sha256:"
                    + "cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685")
            .asCompatibleSubstituteFor("postgres");

    /**
     * The Apache Kafka image rather than Confluent's, driven by Testcontainers'
     * {@code org.testcontainers.kafka.KafkaContainer} - the KRaft-native one, so there is no
     * ZooKeeper container to start and wait for.
     *
     * <p>Addressed by digest alone rather than by tag <em>and</em> digest, unlike the Postgres image
     * above: {@code KafkaContainer} re-parses the name it is given and rejects the combined form. The
     * digest is what the pinning is actually for - it is the immutable identity, and a tag can be
     * re-pointed at different content - so the version it corresponds to is recorded here instead.
     */
    private static final String KAFKA_VERSION = "3.8.0";

    private static final DockerImageName KAFKA_IMAGE = DockerImageName
            .parse("apache/kafka@sha256:"
                    + "c89f315cff967322c5d2021434b32271393cb193aa7ec1d43e97341924e57069")
            .asCompatibleSubstituteFor("apache/kafka");

    @Container
    @ServiceConnection("postgres")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE);

    /**
     * Wired through {@link DynamicPropertySource} rather than {@code @ServiceConnection}: Boot 3.3's
     * connection-details support knows the older {@code org.testcontainers.containers.KafkaContainer}
     * but not this KRaft-native one, so the annotation resolves to nothing and the context fails with
     * a message that names the field rather than the cause.
     */
    @Container
    static KafkaContainer kafka = new KafkaContainer(KAFKA_IMAGE);

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    static {
        // Recorded so the pinned digest is traceable to a release without anyone having to look it up.
        org.slf4j.LoggerFactory.getLogger(KafkaIngressIntegrationTest.class)
                .info("Kafka test broker: apache/kafka {} ({})", KAFKA_VERSION, KAFKA_IMAGE);
    }

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NotificationRequestRepository requests;

    @Autowired
    private NotificationDeliveryRepository deliveries;

    @Autowired
    private DeliveryStatusHistoryRepository history;

    @Autowired
    private RecipientFixtures recipients;

    /**
     * The platform's claim table, which replaced this service's own {@code notification_idempotency}.
     *
     * <p>Cleared between cases for the same reason it always was: these cases replay one record deliberately,
     * and a claim left behind by the previous case would make the replay look like the duplicate it is
     * testing for.
     */
    @Autowired
    private IdempotencyClaimRepository idempotencyClaims;

    @Autowired
    private DeliveryDispatchService dispatchService;

    @BeforeEach
    void resetState() {
        history.deleteAll();
        deliveries.deleteAll();
        requests.deleteAll();
        idempotencyClaims.deleteAll();
        recipients.reset();

        recipients.givenUser(RECIPIENT_ID, RECIPIENT_EMAIL);
    }

    @Test
    @DisplayName("a record on the topic becomes a request and one delivery per recipient")
    void consumesARequest() {
        publish("kafka-key-1", requestJson("kafka-key-1"));

        NotificationRequestEntity request = awaitOneRequest();

        assertThat(request.getSource()).isEqualTo(NotificationSource.KAFKA);
        assertThat(request.getIdempotencyKey()).isEqualTo("kafka-key-1");
        assertThat(request.getTemplateKey()).isEqualTo("password-reset");

        List<?> fannedOut = deliveries.findByRequestId(request.getId());
        assertThat(fannedOut).hasSize(1);
        assertThat(deliveries.findByRequestId(request.getId()).get(0).getStatus())
                .isEqualTo(DeliveryStatus.PENDING);
    }

    /**
     * Kafka delivery is at-least-once by construction - a rebalance, a failed commit or an offset
     * reset all redeliver - so this is not an edge case but the normal path during any deploy. Without
     * the idempotency claim the recipient gets two of everything.
     */
    @Test
    @DisplayName("the same record delivered twice produces one request and one delivery")
    void redeliveryDoesNotDuplicate() {
        String payload = requestJson("kafka-key-dup");
        publish("kafka-key-dup", payload);
        awaitOneRequest();

        publish("kafka-key-dup", payload);

        // Asserted by waiting rather than by an immediate read: the second record has to be consumed
        // before "still one" means anything, and the only observable that it was is the dedup row.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(requests.count()).isEqualTo(1));
        assertThat(deliveries.count()).isEqualTo(1);
    }

    /**
     * An unresolvable recipient is a <em>delivery</em> failure, not a request failure - which is the
     * whole reason the two are separate aggregates. The request is fanned out and the one recipient
     * nobody can reach is recorded {@code DEAD}, so the ask stays explicable; had the request been
     * rejected instead, a batch of five where one address is missing would lose the other four.
     *
     * <p>Acknowledged rather than replayed either way: a permanently un-sendable record retried
     * forever is not a retry, it is a loop that blocks the partition behind it.
     */
    @Test
    @DisplayName("an unresolvable recipient becomes a DEAD delivery on a fanned-out request")
    void unresolvableRecipientIsADeadDeliveryNotARejectedRequest() {
        publish("kafka-key-unresolvable", requestJson("kafka-key-unresolvable", "nobody-at-all"));

        NotificationRequestEntity request = awaitOneRequest();

        assertThat(request.getStatus().name()).isEqualTo("FANNED_OUT");
        assertThat(deliveries.findByRequestId(request.getId())).singleElement()
                .satisfies(delivery -> assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.DEAD));
        // Nothing is dispatched, and the record is not replayed - the consumer returned normally.
        assertThat(dispatchService.processCycle()).isZero();
    }

    /**
     * The one case that genuinely rejects: a request naming only a channel this deployment has no
     * implementation for produces no delivery rows at all, so there is nothing for the failure to be
     * recorded against and the request carries the reason itself.
     */
    @Test
    @DisplayName("a request for a channel with no implementation is rejected, with a reason")
    void requestForAnUnimplementedChannelIsRejected() {
        publish("kafka-key-nochannel",
                requestJson("kafka-key-nochannel", RECIPIENT_ID, "WEBHOOK"));

        NotificationRequestEntity request = awaitOneRequest();

        assertThat(request.getStatus().name()).isEqualTo("REJECTED");
        assertThat(request.getRejectionReason()).isNotBlank();
        assertThat(deliveries.findByRequestId(request.getId())).isEmpty();
    }

    /**
     * A producer that sends no key has accepted a duplicate on redelivery; refusing the record
     * outright would lose a notification somebody wanted.
     */
    @Test
    @DisplayName("a record with no idempotency key is still accepted")
    void missingKeyIsAccepted() {
        publish(null, requestJson(null));

        assertThat(awaitOneRequest().getIdempotencyKey()).isNull();
    }

    private NotificationRequestEntity awaitOneRequest() {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(requests.count()).isEqualTo(1));
        return requests.findAll().get(0);
    }

    private void publish(String key, String payload) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        config.put(ProducerConfig.CLIENT_ID_CONFIG, "test-producer-" + UUID.randomUUID());
        try (KafkaProducer<String, String> producer =
                     new KafkaProducer<>(config, new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(TOPIC, key, payload));
            producer.flush();
        }
    }

    private String requestJson(String idempotencyKey) {
        return requestJson(idempotencyKey, RECIPIENT_ID);
    }

    private String requestJson(String idempotencyKey, String userId) {
        return requestJson(idempotencyKey, userId, "EMAIL");
    }

    /** The topic's payload contract, built here so the test pins the shape a producer actually sends. */
    private String requestJson(String idempotencyKey, String userId, String channel) {
        Map<String, Object> message = new HashMap<>();
        message.put("idempotencyKey", idempotencyKey);
        message.put("templateKey", "password-reset");
        message.put("category", "security");
        message.put("categoryClass", "TRANSACTIONAL");
        message.put("priority", "HIGH");
        message.put("channels", List.of(channel));
        message.put("recipients", List.of(Map.of("userId", userId)));
        message.put("variables", Map.of("resetLink", "https://reset.example/k", "expiresInMinutes", 15));
        try {
            return objectMapper.writeValueAsString(message);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            throw new IllegalStateException("Could not build the test payload", e);
        }
    }
}
