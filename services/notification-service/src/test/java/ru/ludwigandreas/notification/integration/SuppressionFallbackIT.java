package ru.ludwigandreas.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
import ru.ludwigandreas.notification.repository.entity.NotificationDeliveryEntity;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.notification.web.dto.CategoryClassDto;
import ru.ludwigandreas.notification.web.dto.ChannelTypeDto;
import ru.ludwigandreas.notification.web.dto.PriorityDto;
import ru.ludwigandreas.notification.web.dto.RecipientDto;
import ru.ludwigandreas.notification.web.dto.SendNotificationRequest;
import ru.ludwigandreas.outbox.repository.OutboxMessageRepository;

/**
 * The fallback: when every channel a caller asked for is suppressed, the inbox is where the
 * notification lands - if a deployment has said so.
 *
 * <p>The feature is small and every one of its four conditions excludes a case that would otherwise
 * be wrong, so each has a test here. The one worth reading twice is
 * {@link #optedOutOfFallbackGetsNothing()}: a fallback that overrode an opt-out would turn "I do not
 * want this category" into "you will get it somewhere else", which is worse than not delivering it.
 * That condition is also the easiest of the four to leave out, because the feature appears to work
 * without it.
 *
 * <h2>Why chat is enabled here and nowhere else in the suite</h2>
 *
 * <p>This is the only test that needs <b>two</b> interrupting channels: "every requested channel was
 * suppressed" and "one channel survived" differ only when there is more than one to begin with. Chat
 * ships disabled, so without the property below the fan-out's missing-bean guard skips it and both
 * cases collapse into the single-channel one - which is how the first version of this class passed
 * its own assertions while proving half of what it claimed.
 *
 * <p>The base URL points nowhere on purpose. These tests never run a dispatch cycle; the fan-out
 * only requires the bean to exist, and a reachable URL would invite a later test to depend on one by
 * accident.
 */
@AutoConfigureMockMvc
@org.springframework.test.context.TestPropertySource(properties = {
        "ludwig.notification.channels.chat.enabled=true",
        "ludwig.notification.channels.chat.base-url=http://localhost:1/chat"
})
class SuppressionFallbackIT extends NotificationTestBase {

    private static final String RECIPIENT = "fallback-user";
    private static final String OTHER_RECIPIENT = "fallback-other";
    private static final String CATEGORY = "campaigns";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
    private NotificationProperties properties;

    @BeforeEach
    void resetState() {
        contents.deleteAll();
        items.deleteAll();
        history.deleteAll();
        deliveries.deleteAll();
        requests.deleteAll();
        outbox.deleteAll();
        recipients.reset();
        properties.getPreferences().getFallback().clear();

        // Reachable on chat as well, so that a chat delivery which is opted out settles
        // SUPPRESSED rather than DEAD. The distinction is load-bearing here: the fallback fires when
        // every channel was REFUSED by the recipient, and a DEAD delivery means "no destination",
        // which is a different fact. See fallbackDoesNotFireOnUnreachableChannels below.
        recipients.givenUserWithChat(RECIPIENT, "ada@example.com", "@ada");
        recipients.givenUserWithChat(OTHER_RECIPIENT, "grace@example.com", "@grace");
    }

    @AfterEach
    void clearFallback() {
        properties.getPreferences().getFallback().clear();
    }

    @Test
    @DisplayName("every push channel suppressed and a fallback configured produces an inbox item")
    void fallbackDeliversToTheInbox() throws Exception {
        givenFallbackFor(CategoryClassDto.MARKETING);
        recipients.givenOptOut(RECIPIENT, CATEGORY, ChannelType.EMAIL);
        recipients.givenOptOut(RECIPIENT, CATEGORY, ChannelType.CHAT);

        UUID requestId = submit(campaign(Set.of(ChannelTypeDto.EMAIL, ChannelTypeDto.CHAT),
                user(RECIPIENT)), "key-fallback-1");

        List<NotificationDeliveryEntity> fannedOut = deliveries.findByRequestId(requestId);
        assertThat(fannedOut).hasSize(3);
        assertThat(fannedOut)
                .filteredOn(d -> d.getChannel() == ChannelKind.IN_APP)
                .singleElement()
                .satisfies(d -> assertThat(d.getStatus()).isEqualTo(DeliveryStatus.DELIVERED));
        assertThat(items.findAll()).hasSize(1);
    }

    /**
     * One surviving channel means the notification already has a destination, so a fallback would be
     * a second copy of it.
     */
    @Test
    @DisplayName("no fallback is created while any requested channel survives")
    void survivingChannelMeansNoFallback() throws Exception {
        givenFallbackFor(CategoryClassDto.MARKETING);
        recipients.givenOptOut(RECIPIENT, CATEGORY, ChannelType.CHAT);

        UUID requestId = submit(campaign(Set.of(ChannelTypeDto.EMAIL, ChannelTypeDto.CHAT),
                user(RECIPIENT)), "key-fallback-2");

        assertThat(deliveries.findByRequestId(requestId)).hasSize(2);
        assertThat(items.findAll()).isEmpty();
    }

    /**
     * The condition that keeps the feature honest. The recipient has declined the category on the
     * fallback channel too, so there is nothing left that they have consented to.
     */
    @Test
    @DisplayName("a recipient who declined the fallback channel gets nothing")
    void optedOutOfFallbackGetsNothing() throws Exception {
        givenFallbackFor(CategoryClassDto.MARKETING);
        recipients.givenOptOut(RECIPIENT, CATEGORY, ChannelType.EMAIL);
        recipients.givenOptOut(RECIPIENT, CATEGORY, ChannelType.IN_APP);

        UUID requestId = submit(campaign(Set.of(ChannelTypeDto.EMAIL), user(RECIPIENT)),
                "key-fallback-3");

        assertThat(deliveries.findByRequestId(requestId))
                .as("the fallback restores a destination they have not refused; it is not an override")
                .hasSize(1);
        assertThat(items.findAll()).isEmpty();
    }

    @Test
    @DisplayName("with no fallback configured, behaviour is exactly as before")
    void noFallbackConfigured() throws Exception {
        recipients.givenOptOut(RECIPIENT, CATEGORY, ChannelType.EMAIL);

        UUID requestId = submit(campaign(Set.of(ChannelTypeDto.EMAIL), user(RECIPIENT)),
                "key-fallback-4");

        assertThat(deliveries.findByRequestId(requestId)).hasSize(1);
        assertThat(deliveries.findByRequestId(requestId).get(0).getStatus())
                .isEqualTo(DeliveryStatus.SUPPRESSED);
        assertThat(items.findAll()).isEmpty();
    }

    /**
     * A request that already named the fallback channel must not get two inbox items - one from the
     * request and one from the fallback.
     */
    @Test
    @DisplayName("the fallback never duplicates a channel the caller already requested")
    void fallbackDoesNotDuplicateARequestedChannel() throws Exception {
        givenFallbackFor(CategoryClassDto.MARKETING);
        recipients.givenOptOut(RECIPIENT, CATEGORY, ChannelType.EMAIL);

        UUID requestId = submit(campaign(Set.of(ChannelTypeDto.EMAIL, ChannelTypeDto.IN_APP),
                user(RECIPIENT)), "key-fallback-5");

        assertThat(deliveries.findByRequestId(requestId)).hasSize(2);
        assertThat(items.findAll())
                .as("exactly one inbox item, from the request rather than from the fallback")
                .hasSize(1);
    }

    /**
     * Per recipient, not per request. One person's total suppression must not create fallbacks for
     * everybody else in the same request.
     */
    @Test
    @DisplayName("the fallback is evaluated per recipient")
    void fallbackIsPerRecipient() throws Exception {
        givenFallbackFor(CategoryClassDto.MARKETING);
        recipients.givenOptOut(RECIPIENT, CATEGORY, ChannelType.EMAIL);

        UUID requestId = submit(campaign(Set.of(ChannelTypeDto.EMAIL),
                user(RECIPIENT), user(OTHER_RECIPIENT)), "key-fallback-6");

        // Two email deliveries, one suppressed and one pending, plus one fallback for the suppressed
        // recipient only.
        assertThat(deliveries.findByRequestId(requestId)).hasSize(3);
        assertThat(items.findAll()).hasSize(1);
        assertThat(items.findAll().get(0).getOwnerUserId()).isEqualTo(RECIPIENT);
    }

    /**
     * The boundary of the feature, and a real design decision rather than an oversight.
     *
     * <p>The fallback fires when every requested channel was <b>refused by the recipient</b>. A
     * {@code DEAD} delivery is a different fact - "we had no destination at all" - and this change
     * deliberately does not treat it as a trigger, because the two have different answers: a refusal
     * is a preference the inbox respects, while an unreachable address is an operational problem that
     * falling back would hide. Whether unreachability should also fall back is a product question
     * recorded as an open one rather than answered here.
     */
    @Test
    @DisplayName("an unreachable channel is not a suppressed one, and does not trigger the fallback")
    void fallbackDoesNotFireOnUnreachableChannels() throws Exception {
        givenFallbackFor(CategoryClassDto.MARKETING);
        // No chat handle for this user, so the chat delivery settles DEAD rather than SUPPRESSED.
        recipients.givenUser("unreachable-user", "unreachable@example.com");
        recipients.givenOptOut("unreachable-user", CATEGORY, ChannelType.EMAIL);

        UUID requestId = submit(campaign(Set.of(ChannelTypeDto.EMAIL, ChannelTypeDto.CHAT),
                user("unreachable-user")), "key-fallback-7");

        assertThat(deliveries.findByRequestId(requestId))
                .extracting(NotificationDeliveryEntity::getStatus)
                .containsExactlyInAnyOrder(DeliveryStatus.SUPPRESSED, DeliveryStatus.DEAD);
        assertThat(items.findAll())
                .as("a DEAD delivery is not a refusal, so the fallback does not fire")
                .isEmpty();
    }

    private void givenFallbackFor(CategoryClassDto categoryClass) {
        properties.getPreferences().getFallback()
                .put(categoryClass.name(), ChannelType.IN_APP.name());
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

    private static SendNotificationRequest campaign(Set<ChannelTypeDto> channels,
                                                    RecipientDto... recipients) {
        return new SendNotificationRequest("welcome", CATEGORY, CategoryClassDto.MARKETING,
                PriorityDto.BULK, channels, List.of(recipients),
                Map.of("productName", "Ludwig"), null);
    }

    private static RecipientDto user(String userId) {
        return new RecipientDto(userId, null, "en", null, Map.of());
    }
}
