package ru.ludwigandreas.notification.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import ru.ludwigandreas.notification.local.ChatApiStub;
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
 * The chat API goes down and comes back, and the service's own delivery logic handles both.
 *
 * <p>This is the stand-ins used the other way: not in a launched application, but in an ordinary
 * integration test on {@link NotificationTestBase}, with one annotation ({@code @WithPartnerStubs},
 * on {@link PartnerStubsTestBase}). The stub is the same class
 * the local application uses, so the outage a developer reproduces at a breakpoint and the outage
 * this test asserts are the same outage.
 *
 * <p>The failure is a real 503 from a real server. The channel's own classification decides it is
 * retryable and the queue's own backoff schedules the next attempt - nothing is mocked to throw.
 */
class ChatOutageIT extends PartnerStubsTestBase {

    private static final String USER = "chat-user";

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

    @Autowired
    private RecipientFixtures recipients;

    @BeforeEach
    void resetState() {
        history.deleteAll();
        deliveries.deleteAll();
        requests.deleteAll();
        recipients.reset();
        recipients.givenUserWithChat(USER, "chat-user@example.com", "@chat-user");
    }

    @Test
    @DisplayName("a 503 from the chat API is a retryable failure, and the retry after recovery is delivered")
    void outageThenRecovery() throws Exception {
        RunningStub chat = stubs.stub(ChatApiStub.NAME);
        UUID requestId = submitChatWelcome();

        chat.fail(StubFault.UNAVAILABLE);
        // processCycle counts what it delivered, and this attempt delivers nothing.
        assertThat(dispatchService.processCycle()).isZero();

        NotificationDeliveryEntity failed = deliveries.findByRequestId(requestId).get(0);
        // FAILED with a next attempt is this queue's "will be retried"; a terminal failure is DEAD.
        assertThat(failed.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(failed.getLastFailureKind()).isEqualTo(FailureKind.RETRYABLE);
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getLastError()).contains("503");
        assertThat(failed.getNextAttemptAt())
                .as("the queue's backoff scheduled another attempt")
                .isAfter(Instant.now());

        chat.heal();
        // The backoff has done its job by scheduling the retry; the test does not wait it out.
        failed.setNextAttemptAt(Instant.now().minusSeconds(1));
        deliveries.saveAndFlush(failed);
        assertThat(dispatchService.processCycle()).isEqualTo(1);

        NotificationDeliveryEntity delivered = deliveries.findByRequestId(requestId).get(0);
        assertThat(delivered.getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(delivered.getProviderMessageId()).isEqualTo("local-chat-message");
        // One refused, one accepted: the stand-in saw both attempts.
        chat.server().verify(2, postRequestedFor(urlPathEqualTo(ChatApiStub.SEND_PATH)));
    }

    private UUID submitChatWelcome() throws Exception {
        SendNotificationRequest request = new SendNotificationRequest("welcome", "account",
                CategoryClassDto.TRANSACTIONAL, PriorityDto.NORMAL, Set.of(ChannelTypeDto.CHAT),
                List.of(new RecipientDto(USER, null, "en", null, Map.of())),
                Map.of("productName", "Ludwig"), null);
        String body = mockMvc.perform(post("/api/v1/notifications")
                        .with(TestPrincipals.peerService(TestPrincipals.TENANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).path("id").asText());
    }
}
