package ru.ludwigandreas.asyncapi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.springwolf.asyncapi.v3.jackson.AsyncApiSerializerService;
import io.github.springwolf.core.asyncapi.AsyncApiService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import ru.ludwigandreas.messaging.api.MessageHeaders;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;
import ru.ludwigandreas.testsupport.docs.PublishedDocument;

/** The generated document against each scenario of the published-async-document capability. */
@SpringBootTest(classes = ShapeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgresContainerConfiguration.class)
@TestPropertySource(properties = {
        "spring.application.name=shape-service",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.liquibase.enabled=false",
        "spring.kafka.bootstrap-servers=localhost:9",
        "spring.kafka.listener.auto-startup=false",
        "ludwig.outbox.polling.enabled=false",
        "ludwig.kafka.clusters.partner-a.bootstrap-servers=localhost:19092",
        "ludwig.kafka.clusters.internal.bootstrap-servers=localhost:29092",
        "ludwig.messaging.consumers.orders-ingress.cluster=partner-a",
        "ludwig.messaging.consumers.orders-ingress.dead-letter.cluster=internal",
        "ludwig.outbox.routes.invoices.transport=kafka:partner-a",
        "ludwig.outbox.routes.invoices.destination=invoices",
        "ludwig.outbox.routes.invoices.event-types=InvoiceIssued",
        "ludwig.outbox.routes.audit.transport=rest",
        "ludwig.outbox.routes.audit.destination=https://audit.example/events",
        // Named by the declaration, not matched by type: the default route below is Kafka, so an
        // implementation that ignored the declared route would document AuditRecorded as a Kafka send.
        "ludwig.outbox.default-route.transport=kafka",
        "ludwig.outbox.default-route.destination=everything-else"
})
class AsyncApiDocumentShapeIT {

    @Autowired
    private AsyncApiService asyncApi;
    @Autowired
    private AsyncApiSerializerService serializer;

    private JsonNode document;

    @BeforeEach
    void generate() throws Exception {
        document = new ObjectMapper().readTree(serializer.toJsonString(asyncApi.getAsyncAPI()));
    }

    @Test
    void aTypedConsumerOnAPartnerClusterIsBoundToItAndDeadLettersToInternal() {
        assertThat(servers(document.path("channels").path("partner.orders"))).containsExactly("partner-a");
        assertThat(servers(document.path("channels").path("partner.orders.dlt"))).containsExactly("internal");
        assertThat(document.path("components").path("schemas").toString()).contains("\"quantity\"");
        assertThat(document.path("operations").toString()).contains("partner.orders");
    }

    @Test
    void aTextConsumerHasAStringPayloadAndThePlaceholderTopicIsResolved() {
        JsonNode channel = document.path("channels").path("user.settings");
        assertThat(channel.isMissingNode()).isFalse();
        assertThat(servers(channel)).containsExactly("default");
    }

    @Test
    void aDeclaredEventOnAPartnerRouteIsASendOperationWithItsSchema() {
        JsonNode operation = document.path("operations").path("invoices_send_InvoiceIssued");
        assertThat(operation.path("action").asText()).isEqualTo("send");
        assertThat(servers(document.path("channels").path("invoices"))).containsExactly("partner-a");
        assertThat(document.path("components").path("messages").path("InvoiceIssued").path("name").asText())
                .isEqualTo("InvoiceIssued");
        assertThat(document.path("components").path("schemas").toString()).contains("\"amount\"", "\"number\"");
    }

    @Test
    void aDeclaredEventWithoutAKafkaRouteIsAbsent() {
        assertThat(document.toString()).doesNotContain("AuditRecorded", "audit.example");
    }

    @Test
    void everyMessageCarriesTheEnvelopeAndNoSpringTypeHeader() {
        JsonNode envelope = document.path("components").path("schemas").path("LudwigEnvelope").path("properties");
        assertThat(envelope.has(MessageHeaders.EVENT_TYPE)).isTrue();
        assertThat(envelope.has(MessageHeaders.EVENT_VERSION)).isTrue();
        assertThat(envelope.has(MessageHeaders.IDEMPOTENCY_KEY)).isTrue();
        assertThat(envelope.has(MessageHeaders.CORRELATION_ID)).isTrue();
        document.path("components").path("messages").forEach(message ->
                assertThat(message.path("headers").path("$ref").asText()).endsWith("/LudwigEnvelope"));
        assertThat(document.toString()).doesNotContain("__TypeId__");
    }

    @Test
    void theCommittedFormHasNamedServersWithoutAddresses() {
        String canonical = PublishedDocument.canonical(document, PublishedDocument.Kind.ASYNCAPI);
        assertThat(canonical).contains("host: partner-a", "host: internal", "host: default", "title: shape-service")
                .doesNotContain("19092", "29092", "localhost");
    }

    @Test
    void theCanonicalFormIsStableAcrossGenerations() throws Exception {
        JsonNode again = new ObjectMapper().readTree(serializer.toJsonString(asyncApi.getAsyncAPI()));
        assertThat(PublishedDocument.canonical(again, PublishedDocument.Kind.ASYNCAPI))
                .isEqualTo(PublishedDocument.canonical(document, PublishedDocument.Kind.ASYNCAPI));
    }

    private static List<String> servers(JsonNode channel) {
        List<String> names = new ArrayList<>();
        channel.path("servers").forEach(server -> {
            String ref = server.path("$ref").asText();
            names.add(ref.substring(ref.lastIndexOf('/') + 1));
        });
        return names;
    }
}
