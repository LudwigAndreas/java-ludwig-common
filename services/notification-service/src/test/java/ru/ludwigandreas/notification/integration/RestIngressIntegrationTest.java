package ru.ludwigandreas.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.ludwigandreas.notification.repository.NotificationDeliveryRepository;
import ru.ludwigandreas.notification.repository.NotificationRequestRepository;
import ru.ludwigandreas.notification.web.dto.BatchSendRequest;
import ru.ludwigandreas.notification.web.dto.CategoryClassDto;
import ru.ludwigandreas.notification.web.dto.ChannelTypeDto;
import ru.ludwigandreas.notification.web.dto.PriorityDto;
import ru.ludwigandreas.notification.web.dto.RecipientDto;
import ru.ludwigandreas.notification.web.dto.SendNotificationRequest;

/**
 * The REST ingress as the <em>primary</em> ingress, which is what it is on a platform with no broker.
 *
 * <p>The lifecycle test already proves that a single REST submission fans out, renders and sends.
 * What is covered here is the rest of the contract a caller replacing a topic producer depends on:
 * that a batch is genuinely a batch of independent requests, that the 202's location resolves, and
 * that the scope on that location is real.
 */
@AutoConfigureMockMvc
// Three, so the oversize case can be proved with four items rather than a hundred and one. The
// shipped default is 100; what is under test is that the configured number is the one enforced.
@org.springframework.test.context.TestPropertySource(
        properties = "ludwig.notification.ingress.rest.max-batch-size=3")
class RestIngressIntegrationTest extends NotificationTestBase {

    private static final String RECIPIENT_ID = "user-1";
    private static final String RECIPIENT_EMAIL = "ada@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NotificationRequestRepository requests;

    @Autowired
    private NotificationDeliveryRepository deliveries;

    @Autowired
    private RecipientFixtures recipients;

    @BeforeEach
    void resetState() {
        deliveries.deleteAll();
        requests.deleteAll();
        recipients.reset();
        recipients.givenUser(RECIPIENT_ID, RECIPIENT_EMAIL);
    }

    // ---------------------------------------------------------------------------------------------
    // Batch
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a batch becomes one independent request per item")
    void batchFansOutPerItem() throws Exception {
        recipients.givenUser("user-2", "grace@example.com");

        MvcResult result = postBatch(List.of(
                item("first", "batch-key-1", passwordReset(RECIPIENT_ID)),
                item("second", "batch-key-2", passwordReset("user-2"))), 200);

        assertThat(jsonOf(result).path("accepted").asInt()).isEqualTo(2);
        assertThat(jsonOf(result).path("rejected").asInt()).isZero();
        assertThat(requests.count()).isEqualTo(2);
        // Independent requests, not one request with two recipients - which is a different thing and
        // is what the single endpoint is for.
        assertThat(deliveries.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("an item's idempotency key is honoured across calls, like the header on a single send")
    void batchItemsAreIdempotent() throws Exception {
        postBatch(List.of(item("once", "shared-key", passwordReset(RECIPIENT_ID))), 200);
        MvcResult second = postBatch(List.of(item("again", "shared-key", passwordReset(RECIPIENT_ID))), 200);

        assertThat(jsonOf(second).path("results").get(0).path("request").path("duplicate").asBoolean())
                .isTrue();
        assertThat(requests.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a batch above the configured limit is refused with both numbers")
    void oversizedBatchIsRefused() throws Exception {
        List<BatchSendRequest.BatchItem> items = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            items.add(item("item-" + i, "oversize-" + i, passwordReset(RECIPIENT_ID)));
        }

        mockMvc.perform(batchRequest(items))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.submitted").value(4))
                .andExpect(jsonPath("$.limit").value(3));

        assertThat(requests.count()).isZero();
    }

    @Test
    @DisplayName("an empty batch is a bad request rather than a successful no-op")
    void emptyBatchIsRejected() throws Exception {
        mockMvc.perform(batchRequest(List.of())).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------------------------------------
    // Reading a request back
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the location a 202 returns resolves to the request and its deliveries")
    void locationResolves() throws Exception {
        MvcResult accepted = mockMvc.perform(post("/api/v1/notifications")
                        .with(TestPrincipals.peerService(TestPrincipals.TENANT))
                        .header("Idempotency-Key", "location-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(passwordReset(RECIPIENT_ID))))
                .andExpect(status().isAccepted())
                .andReturn();

        String location = accepted.getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        UUID id = UUID.fromString(jsonOf(accepted).path("id").asText());
        assertThat(location).endsWith("/api/v1/notifications/" + id);

        mockMvc.perform(get(location).with(TestPrincipals.peerService(TestPrincipals.TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.deliveries.length()").value(1));
    }

    /**
     * The read is scoped, not merely role-gated. A request names recipients, so a sender holding an
     * id must not be able to read another caller's - which is the whole reason the request became a
     * readable resource with a policy rather than just an id in a header.
     */
    @Test
    @DisplayName("a sender cannot read a request it did not submit")
    void sendersSeeOnlyTheirOwnRequests() throws Exception {
        UUID id = UUID.fromString(jsonOf(mockMvc.perform(post("/api/v1/notifications")
                        .with(TestPrincipals.admin())
                        .header("Idempotency-Key", "admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(passwordReset(RECIPIENT_ID))))
                .andExpect(status().isAccepted())
                .andReturn()).path("id").asText());

        mockMvc.perform(get("/api/v1/notifications/{id}", id)
                        .with(TestPrincipals.peerService(TestPrincipals.TENANT)))
                .andExpect(status().isForbidden());

        // The admin who submitted it reads it back.
        mockMvc.perform(get("/api/v1/notifications/{id}", id).with(TestPrincipals.admin()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an unknown request id is a 404, not an empty 200")
    void unknownRequestIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/notifications/{id}", UUID.randomUUID())
                        .with(TestPrincipals.admin()))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    private MvcResult postBatch(List<BatchSendRequest.BatchItem> items, int expectedStatus)
            throws Exception {
        return mockMvc.perform(batchRequest(items))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder batchRequest(
            List<BatchSendRequest.BatchItem> items) throws Exception {
        return post("/api/v1/notifications/batch")
                .with(TestPrincipals.peerService(TestPrincipals.TENANT))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new BatchSendRequest(items)));
    }

    private com.fasterxml.jackson.databind.JsonNode jsonOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static BatchSendRequest.BatchItem item(String reference, String key,
                                                   SendNotificationRequest request) {
        return new BatchSendRequest.BatchItem(reference, key, request);
    }

    private static SendNotificationRequest passwordReset(String userId) {
        return new SendNotificationRequest("password-reset", "security",
                CategoryClassDto.TRANSACTIONAL, PriorityDto.HIGH, Set.of(ChannelTypeDto.EMAIL),
                List.of(recipient(userId)),
                Map.of("resetLink", "https://reset.example/abc", "expiresInMinutes", 15), null);
    }

    private static RecipientDto recipient(String userId) {
        return new RecipientDto(userId, null, null, null, null);
    }
}
