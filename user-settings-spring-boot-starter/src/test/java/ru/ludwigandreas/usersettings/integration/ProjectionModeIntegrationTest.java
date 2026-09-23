package ru.ludwigandreas.usersettings.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.usersettings.api.SettingLayer;
import ru.ludwigandreas.usersettings.api.SettingsLookup;
import ru.ludwigandreas.usersettings.api.SettingsSubject;
import ru.ludwigandreas.usersettings.api.SettingsWriter;
import ru.ludwigandreas.usersettings.cache.SettingsCache;
import ru.ludwigandreas.usersettings.consent.ConsentGrant;
import ru.ludwigandreas.usersettings.consent.ConsentService;
import ru.ludwigandreas.usersettings.api.ConsentDecision;
import ru.ludwigandreas.usersettings.event.ConsentChangedEvent;
import ru.ludwigandreas.usersettings.event.SettingsEventTypes;
import ru.ludwigandreas.usersettings.event.UserSettingChangedEvent;
import ru.ludwigandreas.usersettings.exception.ReadOnlySettingsException;
import ru.ludwigandreas.usersettings.kafka.SettingsEventListener;
import ru.ludwigandreas.usersettings.repository.UserConsentRepository;
import ru.ludwigandreas.usersettings.repository.UserSettingValueRepository;

/**
 * Projection mode against a real PostgreSQL and a real broker.
 *
 * <p>Records are produced directly onto the topic, in the shape the owner's outbox dispatcher writes
 * them - payload as JSON, type in the {@code event-type} header. That is deliberately not the same
 * thing as running the owner's dispatcher: what is under test here is the consumer's contract with
 * the stream, and driving it from a producer lets a test send an out-of-order event, which a
 * correctly-behaving owner never would.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(UserSettingsTestConfiguration.class)
@TestPropertySource(properties = {
        "ludwig.user-settings.projection.enabled=true",
        "ludwig.user-settings.projection.topic=user.settings",
        "ludwig.user-settings.projection.group-id=user-settings-projection-test",
        "ludwig.user-settings.projection.source-system=account-service",
        "ludwig.user-settings.default-tenant=acme",
        "ludwig.security.enabled=false",
        "ludwig.identity.enabled=false",
        "ludwig.outbox.enabled=false",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.liquibase.change-log=classpath:db/changelog/db.changelog-master.xml",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class ProjectionModeIntegrationTest {

    private static final String TOPIC = "user.settings";
    private static final String TENANT = "acme";
    private static final String SUBJECT = "user-1";
    private static final Instant NOON = Instant.parse("2026-01-02T12:00:00Z");

    /** Pinned by name, version and digest - the same rule every other container here follows. */
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("postgres:16-alpine@sha256:"
                    + "cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685")
            .asCompatibleSubstituteFor("postgres");

    /**
     * The Apache Kafka image rather than Confluent's, driven by Testcontainers' KRaft-native
     * {@code KafkaContainer} - so there is no ZooKeeper container to start and wait for.
     *
     * <p>Addressed by digest alone rather than by tag <em>and</em> digest, unlike the Postgres image
     * above: {@code KafkaContainer} re-parses the name it is given and rejects the combined form. The
     * digest is what the pinning is actually for, so the version it corresponds to is recorded here
     * instead.
     */
    private static final String KAFKA_VERSION = "3.8.0";

    private static final DockerImageName KAFKA_IMAGE = DockerImageName
            .parse("apache/kafka@sha256:"
                    + "c89f315cff967322c5d2021434b32271393cb193aa7ec1d43e97341924e57069")
            .asCompatibleSubstituteFor("apache/kafka");

    @Container
    @ServiceConnection("postgres")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE);

    @Container
    static KafkaContainer kafka = new KafkaContainer(KAFKA_IMAGE);

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private SettingsLookup lookup;

    @Autowired
    private ConsentService consents;

    @Autowired
    private SettingsCache cache;

    @Autowired
    private UserSettingValueRepository values;

    @Autowired
    private UserConsentRepository consentRows;

    @Autowired
    private ApplicationContext context;

    private static SettingsSubject subject() {
        return new SettingsSubject(PrincipalRef.user(SUBJECT), TENANT);
    }

    @BeforeEach
    void reset() {
        values.deleteAll();
        consentRows.deleteAll();
        cache.evictAll();
    }

    @Test
    @DisplayName("projection mode registers no writer at all")
    void projection_mode_has_no_writer_bean() {
        // Business code that injects a SettingsWriter must fail to start here, rather than fail on the
        // first user who tries to save a preference.
        assertThat(context.getBeanNamesForType(SettingsWriter.class)).isEmpty();
    }

    @Test
    @DisplayName("a consent decision cannot be recorded against a replica")
    void consent_writes_are_refused() {
        assertThatThrownBy(() -> consents.grant(PrincipalRef.user(SUBJECT), ConsentGrant.builder()
                .consentKey("marketing.email")
                .textVersion("v3")
                .build()))
                .isInstanceOf(ReadOnlySettingsException.class);
    }

    @Test
    @DisplayName("a setting change on the topic reaches the replica")
    void setting_change_is_projected() {
        publish(change("Europe/Moscow", NOON));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(lookup.get(subject(), IntegrationSettings.TIMEZONE))
                        .isEqualTo(ZoneId.of("Europe/Moscow")));
    }

    @Test
    @DisplayName("an event older than what is stored does not revert the value")
    void out_of_order_event_does_not_revert_the_value() {
        publish(change("Europe/Moscow", NOON));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(storedValue()).isEqualTo("Europe/Moscow"));

        publish(change("Asia/Tokyo", NOON.minusSeconds(3600)));

        // Held rather than awaited: the assertion is that nothing changes, so it has to survive the
        // time the consumer would have needed to change it.
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(storedValue()).isEqualTo("Europe/Moscow"));
    }

    @Test
    @DisplayName("a replayed event leaves exactly one row")
    void replayed_event_is_idempotent() {
        UserSettingChangedEvent event = change("Europe/Moscow", NOON);
        publish(event);
        publish(event);
        publish(event);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(storedValue()).isEqualTo("Europe/Moscow"));
        assertThat(values.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("a newer event replaces the stored value and clears the cached one")
    void newer_event_replaces_the_value_and_evicts_the_cache() {
        publish(change("Europe/Moscow", NOON));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(lookup.get(subject(), IntegrationSettings.TIMEZONE))
                        .isEqualTo(ZoneId.of("Europe/Moscow")));
        // The value is now cached; without an eviction the next read would keep returning it until
        // the TTL expired, which is exactly the staleness the event was supposed to fix.
        assertThat(cache.get(subject())).isPresent();

        publish(change("Asia/Tokyo", NOON.plusSeconds(3600)));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(lookup.get(subject(), IntegrationSettings.TIMEZONE))
                        .isEqualTo(ZoneId.of("Asia/Tokyo")));
    }

    @Test
    @DisplayName("a removal event makes the layer below supply the value again")
    void removal_event_falls_back_to_the_layer_below() {
        publish(tenantChange("Europe/Paris", NOON));
        publish(change("Europe/Moscow", NOON));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(lookup.get(subject(), IntegrationSettings.TIMEZONE))
                        .isEqualTo(ZoneId.of("Europe/Moscow")));

        publish(removal(NOON.plusSeconds(60)));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(lookup.getAll(subject()).resolved(IntegrationSettings.TIMEZONE).layer())
                    .isEqualTo(SettingLayer.TENANT);
            assertThat(lookup.get(subject(), IntegrationSettings.TIMEZONE))
                    .isEqualTo(ZoneId.of("Europe/Paris"));
        });
    }

    @Test
    @DisplayName("a set that arrives after the removal it predates does not resurrect the value")
    void late_set_after_a_removal_does_not_resurrect_the_value() {
        publish(removal(NOON.plusSeconds(60)));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(values.findValue(TENANT,
                        ru.ludwigandreas.usersettings.api.SettingScope.user(SUBJECT), "user.timezone"))
                        .isPresent());

        publish(change("Europe/Moscow", NOON));

        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(lookup.get(subject(), IntegrationSettings.TIMEZONE)).isEqualTo(ZoneId.of("UTC")));
    }

    @Test
    @DisplayName("a consent decision is projected under the owner's own id, once")
    void consent_is_projected_idempotently() {
        UUID consentId = UUID.randomUUID();
        publish(SettingsEventTypes.CONSENT_GRANTED, consent(consentId, ConsentDecision.GRANTED, NOON));
        publish(SettingsEventTypes.CONSENT_GRANTED, consent(consentId, ConsentDecision.GRANTED, NOON));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(consents.currentState(subject()).isGranted("marketing.email")).isTrue());
        assertThat(consentRows.findAll()).hasSize(1);
        assertThat(consentRows.findAll().get(0).getSourceSystem()).isEqualTo("account-service");
    }

    @Test
    @DisplayName("a revocation projected after a grant leaves the ledger showing both")
    void revocation_is_appended_to_the_projected_ledger() {
        publish(SettingsEventTypes.CONSENT_GRANTED,
                consent(UUID.randomUUID(), ConsentDecision.GRANTED, NOON));
        publish(SettingsEventTypes.CONSENT_REVOKED,
                consent(UUID.randomUUID(), ConsentDecision.REVOKED, NOON.plusSeconds(3600)));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(consents.history(subject(), "marketing.email")).hasSize(2);
            assertThat(consents.currentState(subject()).isGranted("marketing.email")).isFalse();
        });
    }

    @Test
    @DisplayName("an event type this replica does not know is ignored rather than fatal")
    void unknown_event_type_is_ignored() {
        // What an owner adding a fourth event type looks like from here. It must not stop the
        // projection advancing for every subject on that partition.
        send("SomethingNewTheOwnerAdded", "{\"whatever\":true}");
        publish(change("Europe/Moscow", NOON));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(storedValue()).isEqualTo("Europe/Moscow"));
    }

    @Test
    @DisplayName("an unparseable payload is dropped and the stream keeps moving")
    void unparseable_payload_does_not_block_the_partition() {
        send(SettingsEventTypes.USER_SETTING_CHANGED, "{ this is not json");
        publish(change("Europe/Moscow", NOON));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(storedValue()).isEqualTo("Europe/Moscow"));
    }

    private String storedValue() {
        return values.findValue(TENANT,
                        ru.ludwigandreas.usersettings.api.SettingScope.user(SUBJECT), "user.timezone")
                .map(row -> row.isRemoved() ? null : row.getValueText())
                .orElse(null);
    }

    private static UserSettingChangedEvent change(String value, Instant occurredAt) {
        return new UserSettingChangedEvent(UUID.randomUUID().toString(), TENANT, SettingLayer.USER,
                SUBJECT, "user.timezone", value, "zone-id", false, occurredAt);
    }

    private static UserSettingChangedEvent tenantChange(String value, Instant occurredAt) {
        return new UserSettingChangedEvent(UUID.randomUUID().toString(), TENANT, SettingLayer.TENANT,
                TENANT, "user.timezone", value, "zone-id", false, occurredAt);
    }

    private static UserSettingChangedEvent removal(Instant occurredAt) {
        return new UserSettingChangedEvent(UUID.randomUUID().toString(), TENANT, SettingLayer.USER,
                SUBJECT, "user.timezone", null, null, true, occurredAt);
    }

    private static ConsentChangedEvent consent(UUID consentId, ConsentDecision decision, Instant occurredAt) {
        return new ConsentChangedEvent(UUID.randomUUID().toString(), consentId, TENANT, SUBJECT,
                "marketing.email", "v3", decision, "en", occurredAt, SUBJECT, "10.0.0.1", "agent", "corr");
    }

    private void publish(UserSettingChangedEvent event) {
        publish(SettingsEventTypes.USER_SETTING_CHANGED, event);
    }

    private void publish(String eventType, Object payload) {
        try {
            send(eventType, MAPPER.writeValueAsString(payload));
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize the test event", e);
        }
    }

    /** The shape the outbox's Kafka dispatcher produces: payload as JSON, type in a header. */
    private void send(String eventType, String payload) {
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, TENANT + "/" + SUBJECT, payload);
        record.headers().add(SettingsEventListener.EVENT_TYPE_HEADER,
                eventType.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        kafkaTemplate.send(record);
    }
}
