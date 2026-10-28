package ru.ludwigandreas.outbox.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.outbox.api.OutboxEvent;
import ru.ludwigandreas.outbox.entity.OutboxMessage;
import ru.ludwigandreas.outbox.entity.OutboxStatus;
import ru.ludwigandreas.outbox.integration.testmodel.StubOutboxDispatcher;
import ru.ludwigandreas.outbox.integration.testmodel.TransactionalPublishHelper;
import ru.ludwigandreas.outbox.repository.OutboxMessageRepository;
import ru.ludwigandreas.outbox.repository.OutboxStatusHistoryRepository;
import ru.ludwigandreas.outbox.scheduler.OutboxProcessingService;
import ru.ludwigandreas.security.admin.AdminInvocation;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;
import ru.ludwigandreas.testsupport.security.TestPrincipalBuilder;

/**
 * The {@code outbox} area through MVC dispatch, the administrative guard and the real outbox tables.
 */
@SpringBootTest(classes = OutboxTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "ludwig.security.enabled=true",
            "ludwig.security.jwt.enabled=false",
            "ludwig.outbox.audit.persist-history=true",
            "ludwig.admin.grants.outbox.read[0]=OPS_SUPPORT",
            "ludwig.admin.grants.outbox.read[1]=OPS_ADMIN",
            "ludwig.admin.grants.outbox.operate[0]=OPS_ADMIN",
            "ludwig.admin.grants.outbox.privileged[0]=OPS_ADMIN"})
@Import({PostgresContainerConfiguration.class, OutboxAdminIntegrationTest.CapturingAudit.class})
@AutoConfigureMockMvc
class OutboxAdminIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    static final List<AuditEvent> AUDIT = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class CapturingAudit {
        @Bean
        AuditSink capturingAuditSink() {
            return AUDIT::add;
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TransactionalPublishHelper publishHelper;

    @Autowired
    private OutboxMessageRepository repository;

    @Autowired
    private OutboxStatusHistoryRepository history;

    @Autowired
    private OutboxProcessingService processingService;

    @Autowired
    private StubOutboxDispatcher dispatcher;

    @BeforeEach
    void reset() {
        dispatcher.reset();
        history.deleteAll();
        repository.deleteAll();
        AUDIT.clear();
    }

    private static RequestPostProcessor admin() {
        return TestPrincipalBuilder.user("alice").roles("OPS_ADMIN").postProcessor();
    }

    private static RequestPostProcessor support() {
        return TestPrincipalBuilder.user("sam").roles("OPS_SUPPORT").postProcessor();
    }

    private UUID messageIn(OutboxStatus status) {
        OutboxMessage message = publishHelper.publish(OutboxEvent.builder().aggregateType("Order")
                .aggregateId(UUID.randomUUID().toString()).eventType("OrderCreated")
                .payload(Map.of("secret", "card-4111")).build()).orElseThrow();
        message.setStatus(status);
        message.setAttempts(status == OutboxStatus.DEAD_LETTER ? message.getMaxAttempts() : 0);
        message.setLastError("broker said no to card-4111");
        repository.save(message);
        return message.getId();
    }

    private static List<AuditEvent> adminEvents() {
        return AUDIT.stream().filter(event -> "admin".equals(event.category())).toList();
    }

    @Test
    @DisplayName("a listing by state carries no payload, no headers and no last error")
    void listingWithoutContents() throws Exception {
        messageIn(OutboxStatus.DEAD_LETTER);
        messageIn(OutboxStatus.PUBLISHED);

        String body = mvc.perform(get("/admin/v1/outbox/messages").param("status", "DEAD_LETTER").with(support()))
                .andReturn().getResponse().getContentAsString();

        JsonNode page = JSON.readTree(body);
        assertThat(page.path("items")).hasSize(1);
        assertThat(page.path("items").get(0).path("status").asText()).isEqualTo("DEAD_LETTER");
        assertThat(body).doesNotContain("card-4111", "payload", "headers", "lastError");
    }

    @Test
    @DisplayName("the single-message view carries its history and last error, still without the payload")
    void detail() throws Exception {
        UUID id = messageIn(OutboxStatus.DEAD_LETTER);

        String body = mvc.perform(get("/admin/v1/outbox/messages/" + id).with(support()))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"lastError\":\"broker said no to card-4111\"").doesNotContain("\"payload\"");
    }

    @Test
    @DisplayName("a dead-lettered message is retried: PENDING, attempts reset, history and audit, then published")
    void retry() throws Exception {
        UUID id = messageIn(OutboxStatus.DEAD_LETTER);

        MvcResult result = mvc.perform(post("/admin/v1/outbox/messages/" + id + "/retry").with(admin())).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        OutboxMessage retried = repository.findById(id).orElseThrow();
        assertThat(retried.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(retried.getAttempts()).isZero();
        assertThat(history.findByOutboxMessageIdOrderByOccurredAtAsc(id))
                .anyMatch(row -> row.getStatus() == OutboxStatus.PENDING
                        && "retried by an administrator".equals(row.getDetail()));
        assertThat(adminEvents()).singleElement().satisfies(event -> {
            assertThat(event.action()).isEqualTo("outbox.operate");
            assertThat(event.actor().subject()).isEqualTo("alice");
            assertThat(event.resource().id()).isEqualTo(id.toString());
        });

        processingService.processBatch();
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    }

    @Test
    @DisplayName("retrying a published message is a 409, recorded as refused, and changes nothing")
    void retryPublished() throws Exception {
        UUID id = messageIn(OutboxStatus.PUBLISHED);

        MvcResult result = mvc.perform(post("/admin/v1/outbox/messages/" + id + "/retry").with(admin())).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(adminEvents()).singleElement()
                .satisfies(event -> assertThat(event.outcome().status().name()).isEqualTo("DENIED"));
    }

    @Test
    @DisplayName("a bulk retry moves the matching messages, reports whether more match, and is one audit event")
    void bulkRetry() throws Exception {
        messageIn(OutboxStatus.DEAD_LETTER);
        messageIn(OutboxStatus.DEAD_LETTER);
        messageIn(OutboxStatus.FAILED);

        MvcResult missingStatus = mvc.perform(post("/admin/v1/outbox/messages/retry").with(admin())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
        MvcResult result = mvc.perform(post("/admin/v1/outbox/messages/retry").with(admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DEAD_LETTER\"}")).andReturn();

        assertThat(missingStatus.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).isEqualTo("{\"moved\":2,\"more\":false}");
        assertThat(repository.findAll()).extracting(OutboxMessage::getStatus)
                .containsExactlyInAnyOrder(OutboxStatus.PENDING, OutboxStatus.PENDING, OutboxStatus.FAILED);
        assertThat(adminEvents()).singleElement()
                .satisfies(event -> assertThat(event.attributes()).containsEntry("moved", 2));
    }

    @Test
    @DisplayName("discard needs a reason and the privileged grant, and a discarded message is never claimed")
    void discard() throws Exception {
        UUID id = messageIn(OutboxStatus.PENDING);

        MvcResult byOperator = mvc.perform(post("/admin/v1/outbox/messages/" + id + "/discard").with(support()))
                .andReturn();
        MvcResult noReason = mvc.perform(post("/admin/v1/outbox/messages/" + id + "/discard").with(admin()))
                .andReturn();
        MvcResult discarded = mvc.perform(post("/admin/v1/outbox/messages/" + id + "/discard")
                .header(AdminInvocation.REASON_HEADER, "superseded by INC-4411 fix").with(admin())).andReturn();

        assertThat(byOperator.getResponse().getStatus()).isEqualTo(403);
        assertThat(noReason.getResponse().getStatus()).isEqualTo(400);
        assertThat(discarded.getResponse().getStatus()).isEqualTo(200);
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(OutboxStatus.DISCARDED);
        assertThat(adminEvents()).singleElement().satisfies(event ->
                assertThat(event.attributes()).containsEntry("reason", "superseded by INC-4411 fix"));

        processingService.processBatch();
        assertThat(dispatcher.dispatched()).isEmpty();
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(OutboxStatus.DISCARDED);
    }
}
