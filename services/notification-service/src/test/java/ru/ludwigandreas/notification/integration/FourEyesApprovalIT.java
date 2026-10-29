package ru.ludwigandreas.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ru.ludwigandreas.outbox.entity.OutboxMessage;
import ru.ludwigandreas.outbox.entity.OutboxStatus;
import ru.ludwigandreas.outbox.repository.OutboxMessageRepository;
import ru.ludwigandreas.security.admin.AdminInvocation;
import ru.ludwigandreas.testsupport.security.TestPrincipalBuilder;

/**
 * Four-eyes approval end to end in this service: {@code outbox.privileged} requires approval, so one administrator's
 * discard is held, they cannot approve it themselves, and a second administrator's approval discards the message
 * through the outbox area's own handler - with the approval kept in job-core's table.
 */
@AutoConfigureMockMvc
class FourEyesApprovalIT extends NotificationTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OutboxMessageRepository outbox;

    @Autowired
    private RecipientFixtures recipients;

    @BeforeEach
    void administrators() {
        recipients.givenUserWithRoles("admin-anna", "anna@notifications.test", "NOTIFICATION_ADMIN");
        recipients.givenUserWithRoles("admin-boris", "boris@notifications.test", "NOTIFICATION_ADMIN");
    }

    private static RequestPostProcessor as(String subject) {
        return TestPrincipalBuilder.user(subject).roles("ROLE_NOTIFICATION_ADMIN").postProcessor();
    }

    private UUID failedMessage() {
        OutboxMessage message = new OutboxMessage();
        message.setAggregateType("Notification");
        message.setAggregateId(UUID.randomUUID().toString());
        message.setEventType("NotificationDelivered");
        message.setEventVersion(1);
        message.setPayload("{}");
        message.setTransport("kafka");
        message.setDestination("notification.lifecycle");
        message.setStatus(OutboxStatus.FAILED);
        message.setMaxAttempts(5);
        message.setNextAttemptAt(Instant.now());
        message.setCreatedAt(Instant.now());
        return outbox.save(message).getId();
    }

    private OutboxStatus statusOf(UUID id) {
        return outbox.findById(id).orElseThrow().getStatus();
    }

    @Test
    @DisplayName("a discard is held, self-approval is refused, and a second administrator's approval discards it")
    void discardNeedsASecondAdministrator() throws Exception {
        UUID id = failedMessage();

        MvcResult held = mvc.perform(post("/admin/v1/outbox/messages/" + id + "/discard")
                .header(AdminInvocation.REASON_HEADER, "INC-42 poison message").with(as("admin-anna"))).andReturn();

        assertThat(held.getResponse().getStatus()).as(held.getResponse().getContentAsString()).isEqualTo(202);
        String approval = JSON.readTree(held.getResponse().getContentAsString()).path("id").asText();
        assertThat(statusOf(id)).isEqualTo(OutboxStatus.FAILED);

        assertThat(mvc.perform(post("/admin/v1/approvals/" + approval + "/approve").with(as("admin-anna")))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(statusOf(id)).isEqualTo(OutboxStatus.FAILED);

        MvcResult approved = mvc.perform(post("/admin/v1/approvals/" + approval + "/approve")
                .with(as("admin-boris"))).andReturn();

        assertThat(approved.getResponse().getStatus()).as(approved.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode body = JSON.readTree(approved.getResponse().getContentAsString());
        assertThat(body.path("operationEnvelope").path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(body.path("outcome").path("status").asInt()).isEqualTo(200);
        assertThat(statusOf(id)).isEqualTo(OutboxStatus.DISCARDED);
        assertThat(JSON.readTree(mvc.perform(get("/admin/v1/approvals/" + approval).with(as("admin-boris")))
                .andReturn().getResponse().getContentAsString()).path("decidedBy").asText()).isEqualTo("admin-boris");
    }
}
