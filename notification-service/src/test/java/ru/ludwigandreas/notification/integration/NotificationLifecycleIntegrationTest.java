package ru.ludwigandreas.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ru.ludwigandreas.notification.repository.DeliveryStatusHistoryRepository;
import ru.ludwigandreas.notification.repository.NotificationDeliveryRepository;
import ru.ludwigandreas.notification.repository.NotificationRequestRepository;
import ru.ludwigandreas.notification.repository.SuppressionRepository;
import ru.ludwigandreas.notification.repository.entity.DeliveryStatus;
import ru.ludwigandreas.notification.repository.entity.NotificationDeliveryEntity;
import ru.ludwigandreas.notification.service.channel.HmacSigner;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.preference.DispatchDecision;
import ru.ludwigandreas.notification.service.preference.usersettings.NotificationSettings;
import ru.ludwigandreas.notification.service.queue.DeliveryDispatchService;
import ru.ludwigandreas.notification.web.dto.CategoryClassDto;
import ru.ludwigandreas.notification.web.dto.ChannelTypeDto;
import ru.ludwigandreas.notification.web.dto.PriorityDto;
import ru.ludwigandreas.notification.web.dto.RecipientDto;
import ru.ludwigandreas.notification.web.dto.SendNotificationRequest;
import ru.ludwigandreas.outbox.repository.OutboxMessageRepository;

/**
 * The whole path, end to end and against real infrastructure: HTTP -> application service ->
 * fan-out -> Postgres queue -> claim -> render -> SMTP, with the real Liquibase migrations applied
 * and a real mail server on the other end.
 *
 * <p>GreenMail rather than a mocked {@code JavaMailSender}, deliberately. A mock proves that a method
 * was called; a real SMTP conversation proves the message is well-formed, actually multipart, and
 * carries the headers that a mailbox provider judges it on - and it is the only way to assert the
 * text alternative exists, which is the thing that silently disappears.
 */
@AutoConfigureMockMvc
class NotificationLifecycleIntegrationTest extends NotificationTestBase {

    private static final String RECIPIENT_ID = "user-1";
    private static final String RECIPIENT_EMAIL = "ada@example.com";
    private static final String RECEIPT_SECRET = "test-receipt-secret";

    /**
     * A real SMTP server in-process, on the standard GreenMail test offset (25 + 3000).
     *
     * <p>{@code withPerMethodLifecycle} so one test's messages never leak into another's assertions -
     * the usual way an email test passes for the wrong reason.
     */
    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
            .withPerMethodLifecycle(true);

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
    private RecipientFixtures recipients;

    @Autowired
    private SuppressionRepository suppressions;

    @Autowired
    private OutboxMessageRepository outbox;

    @BeforeEach
    void resetState() throws Exception {
        history.deleteAll();
        deliveries.deleteAll();
        requests.deleteAll();
        suppressions.deleteAll();
        outbox.deleteAll();
        recipients.reset();

        recipients.givenUser(RECIPIENT_ID, RECIPIENT_EMAIL);
    }

    // ---------------------------------------------------------------------------------------------
    // The happy path
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a request fans out, is claimed, renders and reaches a real mail server")
    void endToEnd() throws Exception {
        UUID requestId = submit(passwordReset(RECIPIENT_ID), "key-happy", 202);

        List<NotificationDeliveryEntity> fannedOut = deliveries.findByRequestId(requestId);
        assertThat(fannedOut).hasSize(1);
        assertThat(fannedOut.get(0).getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(fannedOut.get(0).getRecipientAddress()).isEqualTo(RECIPIENT_EMAIL);

        assertThat(dispatchService.processCycle()).isEqualTo(1);

        assertThat(greenMail.waitForIncomingEmail(5_000L, 1)).isTrue();
        MimeMessage sent = greenMail.getReceivedMessages()[0];
        assertThat(sent.getSubject()).isEqualTo("Reset your password");
        assertThat(sent.getAllRecipients()[0].toString()).isEqualTo(RECIPIENT_EMAIL);
        // Multipart, with the text alternative present - the part that silently disappears and that a
        // mocked sender could never have caught.
        assertThat(sent.getContentType()).startsWith("multipart/");
        String raw = rawContent(sent);
        assertThat(raw).contains("https://reset.example/abc").contains("15 minutes");
        // The correlation header is what joins this message to the request that asked for it.
        assertThat(sent.getHeader("X-Ludwig-Delivery-Id")).isNotNull();

        NotificationDeliveryEntity settled = deliveries.getByIdOrThrow(fannedOut.get(0).getId());
        assertThat(settled.getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(settled.getAttempts()).isEqualTo(1);
        assertThat(settled.getSentAt()).isNotNull();
        assertThat(settled.getTemplateVersion()).isNotBlank();
        assertThat(settled.getProviderMessageId()).isNotBlank();

        // The full trail, which is what an operator actually reads.
        assertThat(history.findAll().stream().map(entry -> entry.getToStatus()).toList())
                .containsSubsequence(DeliveryStatus.ACCEPTED, DeliveryStatus.PENDING, DeliveryStatus.SENT);

        // One outbound lifecycle event, published through the outbox in the outcome transaction.
        assertThat(outbox.findAll()).singleElement()
                .satisfies(message -> {
                    assertThat(message.getEventType()).isEqualTo("NotificationDelivered");
                    assertThat(message.getAggregateType()).isEqualTo("NotificationDelivery");
                    // It carries no address: a topic with a long retention is outside this service's
                    // purge, and a subscriber that needs the destination can ask, authenticated.
                    assertThat(message.getPayload()).doesNotContain(RECIPIENT_EMAIL);
                });
    }

    @Test
    @DisplayName("one request to several recipients becomes one delivery each")
    void fansOutPerRecipient() throws Exception {
        recipients.givenUser("user-2", "grace@example.com");
        recipients.givenUser("user-3", "alan@example.com");

        UUID requestId = submit(new SendNotificationRequest("password-reset", "security",
                CategoryClassDto.TRANSACTIONAL, PriorityDto.HIGH, Set.of(ChannelTypeDto.EMAIL),
                List.of(recipient(RECIPIENT_ID), recipient("user-2"), recipient("user-3")),
                resetVariables(), null), "key-fanout", 202);

        assertThat(deliveries.findByRequestId(requestId)).hasSize(3);
        assertThat(dispatchService.processCycle()).isEqualTo(3);
        assertThat(greenMail.waitForIncomingEmail(5_000L, 3)).isTrue();
    }

    /**
     * The single most important property of the request/delivery split: a request to three people
     * where one is unreachable produces two sends and one recorded, explicable failure - not a
     * rejected request, and not two sends and silence about the third.
     */
    @Test
    @DisplayName("an unreachable recipient is one DEAD delivery, not a failed request")
    void partialFailureIsRepresentable() throws Exception {
        recipients.givenUser("user-2", "grace@example.com");
        // No profile at all for user-missing, so it resolves to nothing.

        UUID requestId = submit(new SendNotificationRequest("password-reset", "security",
                CategoryClassDto.TRANSACTIONAL, PriorityDto.HIGH, Set.of(ChannelTypeDto.EMAIL),
                List.of(recipient(RECIPIENT_ID), recipient("user-2"), recipient("user-missing")),
                resetVariables(), null), "key-partial", 202);

        List<NotificationDeliveryEntity> fannedOut = deliveries.findByRequestId(requestId);
        assertThat(fannedOut).hasSize(3);
        assertThat(fannedOut).filteredOn(d -> d.getStatus() == DeliveryStatus.DEAD).hasSize(1);
        assertThat(fannedOut).filteredOn(d -> d.getStatus() == DeliveryStatus.PENDING).hasSize(2);

        assertThat(requests.getByIdOrThrow(requestId).getStatus().name()).isEqualTo("FANNED_OUT");
        assertThat(dispatchService.processCycle()).isEqualTo(2);
    }

    // ---------------------------------------------------------------------------------------------
    // Idempotency
    // ---------------------------------------------------------------------------------------------

    /**
     * The capability the platform does not have, and without which this service double-sends the
     * moment it runs at more than one replica.
     */
    @Test
    @DisplayName("a redelivered request with the same key creates nothing and reports the original")
    void idempotentRedelivery() throws Exception {
        UUID first = submit(passwordReset(RECIPIENT_ID), "key-dup", 202);

        MvcResult second = mockMvc.perform(post("/api/v1/notifications")
                        .with(TestPrincipals.peerService(TestPrincipals.TENANT))
                        .header("Idempotency-Key", "key-dup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(passwordReset(RECIPIENT_ID))))
                // 200 rather than 202, so a caller retrying after a timeout can tell whether their
                // retry was the one that did the work.
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.id").value(first.toString()))
                .andReturn();

        assertThat(second.getResponse().getContentAsString()).contains(first.toString());
        assertThat(requests.count()).isEqualTo(1);
        assertThat(deliveries.count()).isEqualTo(1);

        assertThat(dispatchService.processCycle()).isEqualTo(1);
        assertThat(greenMail.waitForIncomingEmail(5_000L, 1)).isTrue();
        assertThat(greenMail.getReceivedMessages()).hasSize(1);
    }

    @Test
    @DisplayName("a request with no idempotency key is accepted, and two of them are two requests")
    void noKeyMeansNoDeduplication() throws Exception {
        submit(passwordReset(RECIPIENT_ID), null, 202);
        submit(passwordReset(RECIPIENT_ID), null, 202);

        // Not a bug: a caller that sends no key has explicitly accepted a duplicate on a retry, and
        // inventing one for them would protect nothing while growing the dedup table.
        assertThat(requests.count()).isEqualTo(2);
    }

    // ---------------------------------------------------------------------------------------------
    // Preferences and suppression
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a marketing opt-out suppresses at fan-out, so nothing enters the queue")
    void optOutSuppressesBeforeTheQueue() throws Exception {
        recipients.givenOptOut(RECIPIENT_ID, "campaigns", ChannelType.EMAIL);

        UUID requestId = submit(campaign(RECIPIENT_ID), "key-optout", 202);

        NotificationDeliveryEntity delivery = deliveries.findByRequestId(requestId).get(0);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.SUPPRESSED);
        assertThat(delivery.getSuppressionReason()).isEqualTo(DispatchDecision.Reasons.OPT_OUT);
        assertThat(dispatchService.processCycle()).isZero();
    }

    /**
     * The distinction that makes opt-out safe to implement: a recipient who unsubscribed from
     * everything must still get their password reset.
     */
    @Test
    @DisplayName("a blanket opt-out is overridden by an explicit opt-in for one category")
    void explicitOptInBeatsTheBlanketOptOut() throws Exception {
        // "Nothing at all, except order updates by email" - two settings, no deletion, and the record
        // of the blanket refusal survives. It is the reason the evaluator distinguishes "not set" from
        // "set to false" rather than reading a plain boolean.
        recipients.givenOptOut(RECIPIENT_ID, NotificationSettings.ALL_CATEGORIES, ChannelType.EMAIL);
        recipients.givenOptIn(RECIPIENT_ID, "campaigns", ChannelType.EMAIL);

        UUID requestId = submit(campaign(RECIPIENT_ID), "key-optin", 202);

        assertThat(deliveries.findByRequestId(requestId).get(0).getStatus())
                .isEqualTo(DeliveryStatus.PENDING);
    }

    @Test
    @DisplayName("quiet hours defer the delivery and the delivery records that they did")
    void quietHoursDeferralIsRecordedOnTheDelivery() throws Exception {
        // The window is computed around now so the case does not depend on what time the suite runs.
        LocalTime nowUtc = LocalTime.now(ZoneOffset.UTC);
        recipients.givenTimezone(RECIPIENT_ID, "UTC");
        recipients.givenQuietHours(RECIPIENT_ID,
                nowUtc.minusHours(1).withNano(0).toString(), nowUtc.plusHours(1).withNano(0).toString());

        UUID requestId = submit(campaign(RECIPIENT_ID), "key-quiet", 202);

        NotificationDeliveryEntity delivery = deliveries.findByRequestId(requestId).get(0);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(delivery.getNextAttemptAt()).isAfter(Instant.now());
        // The outcome, on the row. "Why did this arrive at seven in the morning" has to be answerable
        // months later, after the preference it came from has changed.
        assertThat(delivery.isQuietHoursDeferred()).isTrue();
        assertThat(dispatchService.processCycle()).isZero();
    }

    @Test
    @DisplayName("a preference changed after fan-out does not alter a delivery already created")
    void preferencesAreSnapshottedAtFanOut() throws Exception {
        UUID requestId = submit(passwordReset(RECIPIENT_ID), "key-snapshot", 202);
        NotificationDeliveryEntity before = deliveries.findByRequestId(requestId).get(0);
        assertThat(before.getRecipientLocale()).isEqualTo("en");

        // The account service publishes a change; the replica applies it. A retry must not pick it up.
        recipients.givenLocale(RECIPIENT_ID, "ru-RU");
        recipients.givenTimezone(RECIPIENT_ID, "Europe/Moscow");
        assertThat(dispatchService.processCycle()).isEqualTo(1);

        NotificationDeliveryEntity after = deliveries.findById(before.getId()).orElseThrow();
        assertThat(after.getRecipientLocale()).isEqualTo("en");
        assertThat(after.getRecipientTimezone()).isEqualTo("UTC");
        assertThat(after.getRecipientAddress()).isEqualTo(RECIPIENT_EMAIL);
    }

    @Test
    @DisplayName("a transactional notification goes out despite a total opt-out")
    void transactionalIgnoresOptOut() throws Exception {
        recipients.givenOptOut(RECIPIENT_ID, NotificationSettings.ALL_CATEGORIES, ChannelType.EMAIL);

        UUID requestId = submit(passwordReset(RECIPIENT_ID), "key-transactional", 202);

        assertThat(deliveries.findByRequestId(requestId).get(0).getStatus())
                .isEqualTo(DeliveryStatus.PENDING);
        assertThat(dispatchService.processCycle()).isEqualTo(1);
    }

    @Test
    @DisplayName("a suppressed address is never queued, whatever the category")
    void suppressionListHasNoBypass() throws Exception {
        suppress(RECIPIENT_EMAIL, "hard-bounce");

        UUID requestId = submit(passwordReset(RECIPIENT_ID), "key-suppressed", 202);

        NotificationDeliveryEntity delivery = deliveries.findByRequestId(requestId).get(0);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.SUPPRESSED);
        assertThat(delivery.getSuppressionReason())
                .isEqualTo(DispatchDecision.Reasons.SUPPRESSION_LIST);
    }

    /**
     * The fan-out check is not sufficient on its own: a delivery can sit behind a backoff for hours,
     * and a recipient who unsubscribes in that window must not receive what was already enqueued.
     */
    @Test
    @DisplayName("a recipient who unsubscribes after enqueueing is still not sent to")
    void suppressionIsRecheckedBeforeDispatch() throws Exception {
        UUID requestId = submit(passwordReset(RECIPIENT_ID), "key-late-suppression", 202);
        assertThat(deliveries.findByRequestId(requestId).get(0).getStatus())
                .isEqualTo(DeliveryStatus.PENDING);

        suppress(RECIPIENT_EMAIL, "unsubscribe");

        assertThat(dispatchService.processCycle()).isZero();
        assertThat(deliveries.findByRequestId(requestId).get(0).getStatus())
                .isEqualTo(DeliveryStatus.SUPPRESSED);
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // Retry and dead-lettering
    // ---------------------------------------------------------------------------------------------

    /**
     * A missing variable is terminal rather than retryable: the variables were fixed when the request
     * was accepted, so eight attempts would produce eight identical failures and delay the operator
     * learning about it.
     */
    @Test
    @DisplayName("a render failure dead-letters on the first attempt instead of retrying eight times")
    void renderFailureIsTerminal() throws Exception {
        UUID requestId = submit(new SendNotificationRequest("password-reset", "security",
                CategoryClassDto.TRANSACTIONAL, PriorityDto.HIGH, Set.of(ChannelTypeDto.EMAIL),
                List.of(recipient(RECIPIENT_ID)),
                // resetLink is missing, and the template has no default for it.
                Map.of("expiresInMinutes", 15), null), "key-render-fail", 202);

        assertThat(dispatchService.processCycle()).isZero();

        NotificationDeliveryEntity delivery = deliveries.findByRequestId(requestId).get(0);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.DEAD);
        assertThat(delivery.getAttempts()).isEqualTo(1);
        assertThat(delivery.getLastError()).contains("resetLink");
        assertThat(outbox.findAll()).anySatisfy(message ->
                assertThat(message.getEventType()).isEqualTo("NotificationFailed"));
    }

    @Test
    @DisplayName("a template that does not exist dead-letters with a usable message")
    void missingTemplateIsTerminal() throws Exception {
        UUID requestId = submit(new SendNotificationRequest("no-such-template", "security",
                CategoryClassDto.TRANSACTIONAL, PriorityDto.HIGH, Set.of(ChannelTypeDto.EMAIL),
                List.of(recipient(RECIPIENT_ID)), Map.of(), null), "key-no-template", 202);

        dispatchService.processCycle();

        NotificationDeliveryEntity delivery = deliveries.findByRequestId(requestId).get(0);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.DEAD);
        assertThat(delivery.getLastError()).contains("no-such-template");
    }

    // ---------------------------------------------------------------------------------------------
    // Admin API
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("history, content and the two operator actions")
    void adminSurface() throws Exception {
        UUID requestId = submit(passwordReset(RECIPIENT_ID), "key-admin", 202);
        UUID deliveryId = deliveries.findByRequestId(requestId).get(0).getId();
        dispatchService.processCycle();

        mockMvc.perform(get("/api/v1/notifications/deliveries/{id}", deliveryId)
                        .with(TestPrincipals.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                // The address is never returned in full, on any endpoint.
                .andExpect(jsonPath("$.recipientReference").value("user:" + RECIPIENT_ID));

        mockMvc.perform(get("/api/v1/notifications/deliveries/{id}/history", deliveryId)
                        .with(TestPrincipals.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].to").value("ACCEPTED"));

        mockMvc.perform(get("/api/v1/notifications/deliveries/{id}/content", deliveryId)
                        .with(TestPrincipals.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("Reset your password"))
                .andExpect(jsonPath("$.htmlBody").value(org.hamcrest.Matchers.containsString(
                        "https://reset.example/abc")));

        // A SENT delivery cannot be retried: doing so would send the message twice.
        mockMvc.perform(post("/api/v1/notifications/deliveries/{id}/retry", deliveryId)
                        .with(TestPrincipals.admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("error.notification.delivery.illegal-transition"));
    }

    @Test
    @DisplayName("a DEAD delivery can be requeued, and its attempt counter is reset")
    void deadDeliveryCanBeRequeued() throws Exception {
        UUID requestId = submit(new SendNotificationRequest("no-such-template", "security",
                CategoryClassDto.TRANSACTIONAL, PriorityDto.HIGH, Set.of(ChannelTypeDto.EMAIL),
                List.of(recipient(RECIPIENT_ID)), Map.of(), null), "key-requeue", 202);
        UUID deliveryId = deliveries.findByRequestId(requestId).get(0).getId();
        dispatchService.processCycle();
        assertThat(deliveries.getByIdOrThrow(deliveryId).getStatus()).isEqualTo(DeliveryStatus.DEAD);

        mockMvc.perform(post("/api/v1/notifications/deliveries/{id}/retry", deliveryId)
                        .with(TestPrincipals.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                // Reset, because a retry follows a fix to the cause - making it fight for its last
                // attempt would let one further blip dead-letter it again immediately.
                .andExpect(jsonPath("$.attempts").value(0));
    }

    @Test
    @DisplayName("a pending delivery can be cancelled and is then not dispatched")
    void pendingDeliveryCanBeCancelled() throws Exception {
        UUID requestId = submit(passwordReset(RECIPIENT_ID), "key-cancel", 202);
        UUID deliveryId = deliveries.findByRequestId(requestId).get(0).getId();

        mockMvc.perform(post("/api/v1/notifications/deliveries/{id}/cancel", deliveryId)
                        .with(TestPrincipals.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(dispatchService.processCycle()).isZero();
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    /**
     * The endpoint gate and the data scope are different things, and both have to hold. This asserts
     * the first; the second is asserted below.
     */
    @Test
    @DisplayName("a caller with no notification role cannot reach the admin API at all")
    void endpointGateIsEnforced() throws Exception {
        mockMvc.perform(get("/api/v1/notifications/deliveries").with(TestPrincipals.outsider()))
                .andExpect(status().isForbidden());
    }

    /**
     * A load by id does not go through the scoped search query, so without the post-check a caller
     * who guessed or was shown an id could read any tenant's delivery.
     */
    @Test
    @DisplayName("support cannot read another tenant's delivery even by id")
    void dataScopeIsEnforcedOnDirectLoads() throws Exception {
        UUID requestId = submit(passwordReset(RECIPIENT_ID), "key-scope", 202);
        UUID deliveryId = deliveries.findByRequestId(requestId).get(0).getId();

        mockMvc.perform(get("/api/v1/notifications/deliveries/{id}", deliveryId)
                        .with(TestPrincipals.support(TestPrincipals.TENANT)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/notifications/deliveries/{id}", deliveryId)
                        .with(TestPrincipals.support(TestPrincipals.OTHER_TENANT)))
                .andExpect(status().isForbidden());
    }

    /**
     * An empty page rather than a 403: a 403 on a search would confirm to an unauthorized caller that
     * matching deliveries exist.
     */
    @Test
    @DisplayName("a search is scoped in the WHERE clause, so another tenant sees an empty page")
    void searchIsScopedInTheQuery() throws Exception {
        submit(passwordReset(RECIPIENT_ID), "key-search", 202);

        mockMvc.perform(get("/api/v1/notifications/deliveries")
                        .param("$filter", "channel eq 'EMAIL'")
                        .with(TestPrincipals.support(TestPrincipals.TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get("/api/v1/notifications/deliveries")
                        .param("$filter", "channel eq 'EMAIL'")
                        .with(TestPrincipals.support(TestPrincipals.OTHER_TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    /**
     * Filterability is enumerability: an endpoint that answers "does any delivery exist for this
     * address?" is an address-validity oracle, so the column carries no {@code @Filterable} at all.
     */
    @Test
    @DisplayName("the recipient address is unreachable from a query string")
    void addressIsNotFilterable() throws Exception {
        mockMvc.perform(get("/api/v1/notifications/deliveries")
                        .param("$filter", "recipientAddress eq '" + RECIPIENT_EMAIL + "'")
                        .with(TestPrincipals.admin()))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------------------------------------
    // Preview
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a preview renders without sending, and reports the locale it fell back to")
    void previewRendersWithoutSending() throws Exception {
        mockMvc.perform(post("/api/v1/notifications/preview")
                        .with(TestPrincipals.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "templateKey", "password-reset",
                                "channel", "EMAIL",
                                "locale", "fr",
                                "variables", resetVariables()))))
                .andExpect(status().isOk())
                // The single most useful thing it reports: French has no variant, so this would have
                // gone out in English - which otherwise surfaces only as a customer complaint.
                .andExpect(jsonPath("$.resolvedLocale").value("en"))
                .andExpect(jsonPath("$.subject").value("Reset your password"));

        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    @Test
    @DisplayName("a preview with a missing variable is a localized 422, not a blank body")
    void previewFailsLoudlyOnAMissingVariable() throws Exception {
        mockMvc.perform(post("/api/v1/notifications/preview")
                        .with(TestPrincipals.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "templateKey", "password-reset",
                                "channel", "EMAIL",
                                "variables", Map.of()))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("error.notification.template.render-failed"));
    }

    /**
     * Every user-facing message exists in both bundles, and the pipeline picks by Accept-Language.
     * The check that matters is that the two bundles have not drifted - a half-translated response is
     * worse than an English one.
     */
    @Test
    @DisplayName("errors come back in the caller's language")
    void errorsAreLocalized() throws Exception {
        mockMvc.perform(post("/api/v1/notifications/preview")
                        .with(TestPrincipals.admin())
                        .header("Accept-Language", "ru")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "templateKey", "no-such-template",
                                "channel", "EMAIL",
                                "variables", Map.of()))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Шаблон не найден"));
    }

    // ---------------------------------------------------------------------------------------------
    // Receipts
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a signed delivery receipt advances the delivery to DELIVERED")
    void receiptAdvancesTheDelivery() throws Exception {
        UUID requestId = submit(passwordReset(RECIPIENT_ID), "key-receipt", 202);
        dispatchService.processCycle();
        NotificationDeliveryEntity sent = deliveries.findByRequestId(requestId).get(0);

        postReceipt(sent.getProviderMessageId(), "DELIVERED").andExpect(status().isNoContent());

        assertThat(deliveries.getByIdOrThrow(sent.getId()).getStatus())
                .isEqualTo(DeliveryStatus.DELIVERED);
    }

    /**
     * The loop that makes the suppression list self-maintaining, and the reason a bounce is worth
     * processing at all: the bad address comes off the list without anybody doing it by hand.
     */
    @Test
    @DisplayName("a bounce dead-letters the delivery and suppresses the address permanently")
    void bounceFeedsTheSuppressionList() throws Exception {
        UUID requestId = submit(passwordReset(RECIPIENT_ID), "key-bounce", 202);
        dispatchService.processCycle();
        NotificationDeliveryEntity sent = deliveries.findByRequestId(requestId).get(0);

        postReceipt(sent.getProviderMessageId(), "BOUNCED").andExpect(status().isNoContent());

        assertThat(deliveries.getByIdOrThrow(sent.getId()).getStatus()).isEqualTo(DeliveryStatus.DEAD);
        assertThat(suppressions.findActive(
                ru.ludwigandreas.notification.repository.entity.ChannelKind.EMAIL,
                RECIPIENT_EMAIL, Instant.now()))
                .get()
                .satisfies(entry -> {
                    assertThat(entry.getReason()).isEqualTo("hard-bounce");
                    // Permanent: a hard bounce does not stop being true.
                    assertThat(entry.getExpiresAt()).isNull();
                });
    }

    @Test
    @DisplayName("an unsigned or wrongly signed receipt is rejected without saying why")
    void unsignedReceiptIsRejected() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "channel", "EMAIL", "providerMessageId", "whatever", "outcome", "BOUNCED"));

        mockMvc.perform(post("/api/v1/notifications/receipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Provider-Timestamp", Long.toString(Instant.now().getEpochSecond()))
                        .header("X-Provider-Signature", "deadbeef")
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("error.notification.receipt.invalid-signature"));
    }

    /**
     * Without a timestamp inside the signature, a receipt captured once is valid forever - and a
     * replayed bounce suppresses a real address at a moment of the attacker's choosing.
     */
    @Test
    @DisplayName("a correctly signed but stale receipt is rejected")
    void staleReceiptIsRejected() throws Exception {
        long longAgo = Instant.now().minusSeconds(3600).getEpochSecond();
        String body = objectMapper.writeValueAsString(Map.of(
                "channel", "EMAIL", "providerMessageId", "whatever", "outcome", "BOUNCED"));

        mockMvc.perform(post("/api/v1/notifications/receipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Provider-Timestamp", Long.toString(longAgo))
                        .header("X-Provider-Signature", HmacSigner.sign(RECEIPT_SECRET, longAgo, body))
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a receipt for an unknown message id is a 404, so the provider stops retrying")
    void unknownReceiptIsNotFound() throws Exception {
        postReceipt("no-such-message", "DELIVERED")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("error.notification.receipt.unknown-message"));
    }

    // ---------------------------------------------------------------------------------------------
    // Validation
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a recipient naming both a user and an address is rejected as ambiguous")
    void ambiguousRecipientIsRejected() throws Exception {
        SendNotificationRequest request = new SendNotificationRequest("password-reset", "security",
                CategoryClassDto.TRANSACTIONAL, PriorityDto.HIGH, Set.of(ChannelTypeDto.EMAIL),
                List.of(new RecipientDto(RECIPIENT_ID, RECIPIENT_EMAIL, null, null, null)),
                resetVariables(), null);

        mockMvc.perform(post("/api/v1/notifications")
                        .with(TestPrincipals.peerService(TestPrincipals.TENANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("recipients[0].exactlyOneIdentifier"));
    }

    /**
     * The key becomes a directory name in the template tree, so a traversal attempt must not get as
     * far as the resolver.
     */
    @Test
    @DisplayName("a template key containing a path traversal is rejected by validation")
    void templateKeyIsPathSafe() throws Exception {
        SendNotificationRequest request = new SendNotificationRequest("../../etc/passwd", "security",
                CategoryClassDto.TRANSACTIONAL, PriorityDto.HIGH, Set.of(ChannelTypeDto.EMAIL),
                List.of(recipient(RECIPIENT_ID)), resetVariables(), null);

        mockMvc.perform(post("/api/v1/notifications")
                        .with(TestPrincipals.peerService(TestPrincipals.TENANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    private UUID submit(SendNotificationRequest request, String idempotencyKey, int expectedStatus)
            throws Exception {
        var builder = post("/api/v1/notifications")
                .with(TestPrincipals.peerService(TestPrincipals.TENANT))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request));
        if (idempotencyKey != null) {
            builder = builder.header("Idempotency-Key", idempotencyKey);
        }
        MvcResult result = mockMvc.perform(builder)
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("id").asText());
    }

    private org.springframework.test.web.servlet.ResultActions postReceipt(String messageId,
                                                                           String outcome)
            throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "channel", "EMAIL",
                "providerMessageId", messageId,
                "outcome", outcome,
                "occurredAt", Instant.now().toString()));
        long timestamp = Instant.now().getEpochSecond();
        return mockMvc.perform(post("/api/v1/notifications/receipts")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Provider-Timestamp", Long.toString(timestamp))
                .header("X-Provider-Signature", HmacSigner.sign(RECEIPT_SECRET, timestamp, body))
                .content(body));
    }

    private void suppress(String address, String reason) throws Exception {
        mockMvc.perform(post("/api/v1/notifications/suppressions")
                        .with(TestPrincipals.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "channel", "EMAIL", "address", address, "reason", reason))))
                .andExpect(status().isOk());
    }

    private void writeAs(RequestPostProcessor caller,
                         org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
                         Map<String, Object> body) throws Exception {
        mockMvc.perform(builder.with(caller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private static SendNotificationRequest passwordReset(String userId) {
        return new SendNotificationRequest("password-reset", "security",
                CategoryClassDto.TRANSACTIONAL, PriorityDto.HIGH, Set.of(ChannelTypeDto.EMAIL),
                List.of(recipient(userId)), resetVariables(), null);
    }

    private static SendNotificationRequest campaign(String userId) {
        return new SendNotificationRequest("welcome", "campaigns",
                CategoryClassDto.MARKETING, PriorityDto.BULK, Set.of(ChannelTypeDto.EMAIL),
                List.of(recipient(userId)), Map.of("productName", "Ludwig"), null);
    }

    private static RecipientDto recipient(String userId) {
        return new RecipientDto(userId, null, null, null, null);
    }

    private static Map<String, Object> resetVariables() {
        return Map.of("resetLink", "https://reset.example/abc", "expiresInMinutes", 15);
    }

    private static String rawContent(MimeMessage message) throws Exception {
        return com.icegreen.greenmail.util.GreenMailUtil.getWholeMessage(message);
    }
}
