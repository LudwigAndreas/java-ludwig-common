package ru.ludwigandreas.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import ru.ludwigandreas.notification.local.WebhookReceiverStub;
import ru.ludwigandreas.notification.repository.DeliveryStatusHistoryRepository;
import ru.ludwigandreas.notification.repository.NotificationDeliveryRepository;
import ru.ludwigandreas.notification.repository.NotificationRequestRepository;
import ru.ludwigandreas.notification.repository.entity.DeliveryStatus;
import ru.ludwigandreas.notification.repository.entity.FailureKind;
import ru.ludwigandreas.notification.repository.entity.NotificationDeliveryEntity;
import ru.ludwigandreas.notification.service.queue.DeliveryDispatchService;
import ru.ludwigandreas.notification.web.dto.CategoryClassDto;
import ru.ludwigandreas.notification.web.dto.ChannelTypeDto;
import ru.ludwigandreas.notification.web.dto.PriorityDto;
import ru.ludwigandreas.notification.web.dto.RecipientDto;
import ru.ludwigandreas.notification.web.dto.SendNotificationRequest;
import ru.ludwigandreas.testsupport.local.stub.RunningStub;
import ru.ludwigandreas.testsupport.local.stub.StubFault;
import ru.ludwigandreas.testsupport.local.stub.StubServers;

/**
 * A webhook endpoint that answers more slowly than the service is willing to wait.
 *
 * <p>The read timeout is the service's own setting, shortened to 500 ms in
 * {@link PartnerStubsTestBase} so the test takes half a second rather than ten; the stand-in's delay
 * is five seconds. What is asserted is that the service
 * gives up at its timeout, not at the endpoint's convenience, and that it treats the attempt as
 * worth repeating.
 */
class SlowWebhookIT extends PartnerStubsTestBase {

    private static final Duration READ_TIMEOUT = Duration.ofMillis(500);
    private static final Duration MARGIN = Duration.ofSeconds(2);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StubServers stubs;

    @Autowired
    private DeliveryDispatchService dispatchService;

    @Autowired
    private NotificationDeliveryRepository deliveries;

    @Autowired
    private NotificationRequestRepository requests;

    @Autowired
    private DeliveryStatusHistoryRepository history;

    @BeforeEach
    void resetState() {
        history.deleteAll();
        deliveries.deleteAll();
        requests.deleteAll();
    }

    @Test
    @DisplayName("a webhook slower than the read timeout fails at the timeout and is scheduled again")
    void slowEndpointIsARetryableFailure() throws Exception {
        RunningStub webhooks = stubs.stub(WebhookReceiverStub.NAME);
        UUID requestId = submitWebhook(WebhookReceiverStub.endpoint(webhooks));
        webhooks.fail(StubFault.SLOW);

        Instant started = Instant.now();
        // processCycle counts what it delivered, and this attempt delivers nothing.
        assertThat(dispatchService.processCycle()).isZero();
        Duration took = Duration.between(started, Instant.now());

        assertThat(took)
                .as("the service waited for its own read timeout, not for the endpoint's five seconds")
                .isLessThan(READ_TIMEOUT.plus(MARGIN));
        NotificationDeliveryEntity delivery = deliveries.findByRequestId(requestId).get(0);
        // FAILED with a next attempt is this queue's "will be retried"; a terminal failure is DEAD.
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(delivery.getLastFailureKind()).isEqualTo(FailureKind.RETRYABLE);
        assertThat(delivery.getAttempts()).isEqualTo(1);
        assertThat(delivery.getNextAttemptAt()).isAfter(Instant.now());
    }

    private UUID submitWebhook(String endpoint) throws Exception {
        SendNotificationRequest request = new SendNotificationRequest(WebhookReceiverStub.TEMPLATE_KEY,
                "orders", CategoryClassDto.TRANSACTIONAL, PriorityDto.NORMAL, Set.of(ChannelTypeDto.WEBHOOK),
                List.of(new RecipientDto(null, endpoint, "en", null, Map.of())),
                Map.of("orderId", "A-1001"), null);
        String body = mockMvc.perform(post("/api/v1/notifications")
                        .with(TestPrincipals.peerService(TestPrincipals.TENANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).path("id").asText());
    }
}
