package ru.ludwigandreas.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import ru.ludwigandreas.notification.repository.DeliveryStatusHistoryRepository;
import ru.ludwigandreas.notification.repository.InboxItemContentRepository;
import ru.ludwigandreas.notification.repository.InboxItemRepository;
import ru.ludwigandreas.notification.repository.NotificationDeliveryRepository;
import ru.ludwigandreas.notification.repository.NotificationRequestRepository;
import ru.ludwigandreas.notification.repository.entity.ChannelKind;
import ru.ludwigandreas.notification.repository.entity.DeliveryStatus;
import ru.ludwigandreas.notification.repository.entity.InboxItemContentEntity;
import ru.ludwigandreas.notification.repository.entity.InboxItemEntity;
import ru.ludwigandreas.notification.repository.entity.NotificationDeliveryEntity;
import ru.ludwigandreas.notification.service.channel.ChannelRegistry;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.queue.DeliveryDispatchService;
import ru.ludwigandreas.notification.web.dto.CategoryClassDto;
import ru.ludwigandreas.notification.web.dto.ChannelTypeDto;
import ru.ludwigandreas.notification.web.dto.PriorityDto;
import ru.ludwigandreas.notification.web.dto.RecipientDto;
import ru.ludwigandreas.notification.web.dto.SendNotificationRequest;
import ru.ludwigandreas.outbox.repository.OutboxMessageRepository;

/**
 * The in-app settlement, end to end: HTTP in, an inbox item out, and nothing in the queue.
 *
 * <p>The claim this test exists to prove is not "a row was written" but <b>when</b> it was written.
 * An in-app delivery is settled inside the transaction that accepts the request, so the item is
 * readable the instant the caller's 202 returns and there is no interval in which a notification
 * reported as delivered is not yet there. Every other channel in this service is the opposite -
 * accepted now, sent later by a poller - so the assertion below that the delivery is already
 * terminal in the response, with the dispatcher never having run, is the whole design in one place.
 */
@AutoConfigureMockMvc
class InAppSettlementIT extends NotificationTestBase {

    private static final String RECIPIENT_ID = "user-inbox-1";
    private static final String OTHER_RECIPIENT_ID = "user-inbox-2";
    private static final String RECIPIENT_EMAIL = "ada@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DeliveryDispatchService dispatchService;

    @Autowired
    private NotificationRequestRepository requests;

    @Autowired
    private NotificationDeliveryRepository deliveries;

    @Autowired
    private DeliveryStatusHistoryRepository history;

    @Autowired
    private InboxItemRepository items;

    @Autowired
    private InboxItemContentRepository contents;

    @Autowired
    private RecipientFixtures recipients;

    @Autowired
    private OutboxMessageRepository outbox;

    @Autowired
    private ChannelRegistry channelRegistry;

    @BeforeEach
    void resetState() {
        contents.deleteAll();
        items.deleteAll();
        history.deleteAll();
        deliveries.deleteAll();
        requests.deleteAll();
        outbox.deleteAll();
        recipients.reset();

        recipients.givenUser(RECIPIENT_ID, RECIPIENT_EMAIL);
        recipients.givenUser(OTHER_RECIPIENT_ID, "grace@example.com");
    }

    @Test
    @DisplayName("an in-app request is already delivered when the caller's 202 returns")
    void settledInTheAcceptingTransaction() throws Exception {
        UUID requestId = submit(welcome(Set.of(ChannelTypeDto.IN_APP), user(RECIPIENT_ID)),
                "key-in-app-happy");

        List<NotificationDeliveryEntity> fannedOut = deliveries.findByRequestId(requestId);
        assertThat(fannedOut).hasSize(1);
        NotificationDeliveryEntity delivery = fannedOut.get(0);

        assertThat(delivery.getChannel()).isEqualTo(ChannelKind.IN_APP);
        // Terminal already, with no dispatcher having run. DELIVERED and not SENT: SENT means
        // "handed to a provider that has not confirmed", and there is no provider to confirm.
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);

        List<InboxItemEntity> stored = items.findAll();
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getOwnerUserId()).isEqualTo(RECIPIENT_ID);
        assertThat(stored.get(0).getDeliveryId()).isEqualTo(delivery.getId());
        assertThat(stored.get(0).getRequestId()).isEqualTo(requestId);
        // Unread, unseen, undismissed: the three instants the recipient moves and nobody else.
        assertThat(stored.get(0).getReadAt()).isNull();
        assertThat(stored.get(0).getSeenAt()).isNull();
        assertThat(stored.get(0).getDismissedAt()).isNull();

        InboxItemContentEntity content = contents.findById(stored.get(0).getId()).orElseThrow();
        assertThat(content.getSubject()).isEqualTo("Welcome to Ludwig");
        assertThat(content.getBodyHtml()).contains("Your account is ready");
        // The text alternative is the part that silently disappears, here as in email.
        assertThat(content.getBodyText()).contains("Your account is ready");
    }

    /**
     * The claim query must behave exactly as it would if the in-app rows were not there. Asserted by
     * running a full dispatch cycle rather than by inspecting the SQL, because what matters is the
     * observable result: nothing claimed, and the terminal row untouched.
     */
    @Test
    @DisplayName("the dispatcher never claims an in-app delivery")
    void claimIgnoresInAppDeliveries() throws Exception {
        submit(welcome(Set.of(ChannelTypeDto.IN_APP), user(RECIPIENT_ID)), "key-in-app-claim");

        assertThat(dispatchService.processCycle()).isZero();

        assertThat(deliveries.findAll())
                .allSatisfy(d -> assertThat(d.getStatus()).isEqualTo(DeliveryStatus.DELIVERED));
        assertThat(deliveries.findAll())
                .allSatisfy(d -> assertThat(d.getClaimedBy()).isNull());
    }

    /**
     * The same request on two channels. The point is independence: the in-app delivery is terminal
     * before the dispatcher runs and the email is still waiting for it, so neither settlement can be
     * an artefact of the other.
     */
    @Test
    @DisplayName("in-app and email settle independently in one request")
    void twoChannelsSettleIndependently() throws Exception {
        UUID requestId = submit(
                welcome(Set.of(ChannelTypeDto.IN_APP, ChannelTypeDto.EMAIL), user(RECIPIENT_ID)),
                "key-in-app-mixed");

        Map<ChannelKind, DeliveryStatus> byChannel = statusesByChannel(requestId);

        assertThat(byChannel).containsEntry(ChannelKind.IN_APP, DeliveryStatus.DELIVERED);
        assertThat(byChannel).containsEntry(ChannelKind.EMAIL, DeliveryStatus.PENDING);
        assertThat(items.findAll()).hasSize(1);
    }

    /**
     * A literal address has no subject behind it, so there is no inbox to attribute the notification
     * to. One terminal delivery, and - this is the operative assertion - the request is still
     * accepted and the other recipient still gets theirs.
     */
    @Test
    @DisplayName("a literal-address recipient is one terminal delivery, not a rejected request")
    void addressRecipientIsTerminalAndDoesNotFailTheRequest() throws Exception {
        UUID requestId = submit(
                welcome(Set.of(ChannelTypeDto.IN_APP), user(RECIPIENT_ID), address("x@example.com")),
                "key-in-app-address");

        List<NotificationDeliveryEntity> fannedOut = deliveries.findByRequestId(requestId);
        assertThat(fannedOut).hasSize(2);

        assertThat(fannedOut).anySatisfy(d -> {
            assertThat(d.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
            assertThat(d.getRecipientUserId()).isEqualTo(RECIPIENT_ID);
        });
        assertThat(fannedOut).anySatisfy(d ->
                assertThat(d.getStatus()).isEqualTo(DeliveryStatus.DEAD));

        // One inbox item, for the recipient who had an identity.
        assertThat(items.findAll()).hasSize(1);
        assertThat(items.findAll().get(0).getOwnerUserId()).isEqualTo(RECIPIENT_ID);
    }

    /**
     * No address is stored, and the reason it matters is that {@code recipient_address} is the one
     * column on the delivery that carries direct personal data, is not filterable, and is scrubbed by
     * the purge. A passive channel has nothing to put there, so storing a placeholder would be a
     * value that looks like an address and is not one.
     */
    @Test
    @DisplayName("an in-app delivery stores no recipient address")
    void noAddressIsStored() throws Exception {
        UUID requestId = submit(welcome(Set.of(ChannelTypeDto.IN_APP), user(RECIPIENT_ID)),
                "key-in-app-pii");

        assertThat(deliveries.findByRequestId(requestId).get(0).getRecipientAddress()).isNull();
    }

    /**
     * The carve-out's boundary, asserted against the real application context. ArchUnit cannot
     * express this: whether a transport supports a channel is a predicate's return value, not a
     * property of a type's shape or of the source text.
     */
    @Test
    @DisplayName("no transport implementation is registered for the passive channel")
    void noTransportSupportsInApp() {
        assertThat(channelRegistry.find(ChannelType.IN_APP)).isEmpty();
        // The control: the registry does resolve the channels that do have transports, so the
        // assertion above is about IN_APP rather than about an empty registry.
        assertThat(channelRegistry.find(ChannelType.EMAIL)).isPresent();
    }

    /**
     * The status trail is the reason {@code ACCEPTED} is a real state, and a settled in-app delivery
     * must leave the same kind of readable record as a suppressed or batched one: created, then
     * settled with a reason.
     */
    @Test
    @DisplayName("the settlement leaves a readable status trail")
    void statusTrailRecordsTheSettlement() throws Exception {
        UUID requestId = submit(welcome(Set.of(ChannelTypeDto.IN_APP), user(RECIPIENT_ID)),
                "key-in-app-trail");
        UUID deliveryId = deliveries.findByRequestId(requestId).get(0).getId();

        assertThat(history.findAll())
                .filteredOn(h -> h.getDeliveryId().equals(deliveryId))
                .extracting(h -> h.getToStatus())
                .contains(DeliveryStatus.DELIVERED);
    }

    private Map<ChannelKind, DeliveryStatus> statusesByChannel(UUID requestId) {
        return deliveries.findByRequestId(requestId).stream()
                .collect(java.util.stream.Collectors.toMap(
                        NotificationDeliveryEntity::getChannel,
                        NotificationDeliveryEntity::getStatus));
    }

    private UUID submit(SendNotificationRequest request, String idempotencyKey) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/notifications")
                        .with(TestPrincipals.peerService(TestPrincipals.TENANT))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is(202))
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("id").asText());
    }

    private static SendNotificationRequest welcome(Set<ChannelTypeDto> channels,
                                                   RecipientDto... recipients) {
        return new SendNotificationRequest("welcome", "account", CategoryClassDto.TRANSACTIONAL,
                PriorityDto.NORMAL, channels, List.of(recipients),
                Map.of("productName", "Ludwig"), null);
    }

    private static RecipientDto user(String userId) {
        return new RecipientDto(userId, null, "en", null, Map.of());
    }

    private static RecipientDto address(String address) {
        return new RecipientDto(null, address, "en", null, Map.of());
    }
}
