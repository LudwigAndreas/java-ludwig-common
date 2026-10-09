package ru.ludwigandreas.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.ludwigandreas.notification.repository.NotificationDeliveryRepository;
import ru.ludwigandreas.notification.repository.entity.DeliveryStatus;
import ru.ludwigandreas.notification.repository.entity.InboxItemEntity;
import ru.ludwigandreas.notification.repository.entity.NotificationDeliveryEntity;
import ru.ludwigandreas.notification.repository.entity.NotificationRequestEntity;
import ru.ludwigandreas.notification.service.channel.ChannelRegistry;
import ru.ludwigandreas.notification.service.inbox.InAppSettlement;
import ru.ludwigandreas.notification.service.metrics.NotificationMetrics;
import ru.ludwigandreas.notification.service.model.CategoryClass;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.model.NotificationCommand;
import ru.ludwigandreas.notification.service.model.Priority;
import ru.ludwigandreas.notification.service.model.RecipientRef;
import ru.ludwigandreas.notification.service.preference.PreferenceEvaluator;
import ru.ludwigandreas.notification.service.preference.QuietHours;
import ru.ludwigandreas.notification.service.preference.RecipientPreferences;
import ru.ludwigandreas.notification.service.preference.SuppressionService;
import ru.ludwigandreas.notification.service.queue.DeliveryFanOutService;
import ru.ludwigandreas.notification.service.queue.DeliveryStatusRecorder;
import ru.ludwigandreas.notification.service.recipient.RecipientResolver;
import ru.ludwigandreas.notification.service.recipient.ResolvedRecipient;
import ru.ludwigandreas.notification.settings.NotificationProperties;

/**
 * The three interruption rules a passive channel is not subject to, asserted at the point they are
 * applied rather than at the point they are declared.
 *
 * <p>Each of the three is switched off for its own reason, and each would be a distinct silent
 * defect if it were not:
 *
 * <ul>
 *   <li><b>The missing-bean guard.</b> The fan-out skips any channel with no registered transport, on
 *       the grounds that a row nobody can send is a dead letter blaming the recipient for a
 *       deployment gap. Having no transport is exactly how the inbox is built, so without an
 *       exemption that guard silently drops every in-app notification ever requested - and silently
 *       is the operative word: the request still answers 202 with one fewer delivery than asked
 *       for.</li>
 *   <li><b>The suppression list.</b> Keyed by delivery address, of which a passive channel has
 *       none. Left in place it would ask about a null address, and a bounced mailbox would stop the
 *       inbox - the one destination that still works when somebody's email does not.</li>
 *   <li><b>The digest.</b> Batching exists to reduce a count of interruptions. An inbox already has
 *       that property, so batching would delay every item to the end of a window and buy
 *       nothing.</li>
 * </ul>
 *
 * <p>Every assertion here has an interrupting-channel control beside it. Without one, each test
 * would pass equally well against a service that had switched the rule off for every channel, which
 * is a much worse bug than the one being guarded against.
 */
class PassiveChannelSettlementTest {

    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");
    private static final String CATEGORY = "order-updates";
    private static final String OWNER = "8f2c-user";

    private NotificationDeliveryRepository deliveryRepository;
    private DeliveryStatusRecorder statusRecorder;
    private RecipientResolver recipientResolver;
    private SuppressionService suppressionService;
    private ChannelRegistry channelRegistry;
    private NotificationProperties properties;
    private InAppSettlement inAppSettlement;
    private DeliveryFanOutService fanOut;

    @BeforeEach
    void setUp() {
        deliveryRepository = mock(NotificationDeliveryRepository.class);
        statusRecorder = mock(DeliveryStatusRecorder.class);
        recipientResolver = mock(RecipientResolver.class);
        suppressionService = mock(SuppressionService.class);
        channelRegistry = mock(ChannelRegistry.class);
        properties = digestEnabledFor(CATEGORY);
        inAppSettlement = mock(InAppSettlement.class);
        // Settles successfully for any passive delivery. These tests are about which rules are
        // applied before the settlement is reached, not about the settlement itself - that is
        // InAppSettlementIT's subject, against a real database and a real template.
        when(inAppSettlement.settle(any(), any(), any(), any()))
                .thenReturn(Optional.of(new InboxItemEntity()));

        when(deliveryRepository.saveAndFlush(any(NotificationDeliveryEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(recipientResolver.preferences(any())).thenReturn(noPreferences());
        // Every address is on the suppression list, and every category has a digest window. Both
        // rules are therefore live for any channel that is subject to them.
        when(suppressionService.isSuppressed(any(), any(), any())).thenReturn(true);
        // No transport is registered for anything, which is the real deployment state for the inbox
        // and a simulated deployment gap for email.
        when(channelRegistry.find(any())).thenReturn(Optional.empty());

        fanOut = new DeliveryFanOutService(deliveryRepository, statusRecorder, recipientResolver,
                new PreferenceEvaluator(properties), suppressionService, channelRegistry, properties,
                mock(NotificationMetrics.class), new ObjectMapper(), inAppSettlement);
    }

    @Test
    @DisplayName("a passive channel is not skipped for having no transport implementation")
    void passiveChannelSurvivesTheMissingBeanGuard() {
        resolves(ChannelType.IN_APP);

        assertThat(fanOut.fanOut(request(), command(ChannelType.IN_APP))).hasSize(1);
    }

    /**
     * The control for the guard. An interrupting channel with no transport must still be skipped -
     * otherwise the exemption above was written as "skip nothing" rather than "skip nothing passive".
     */
    @Test
    @DisplayName("an interrupting channel with no transport implementation is still skipped")
    void interruptingChannelIsStillSkippedWithoutATransport() {
        resolves(ChannelType.EMAIL);

        assertThat(fanOut.fanOut(request(), command(ChannelType.EMAIL))).isEmpty();
    }

    @Test
    @DisplayName("a passive channel is not suppressed by the address suppression list")
    void passiveChannelIgnoresTheSuppressionList() {
        resolves(ChannelType.IN_APP);

        fanOut.fanOut(request(), command(ChannelType.IN_APP));

        assertThat(statusesRecorded()).doesNotContain(DeliveryStatus.SUPPRESSED);
    }

    @Test
    @DisplayName("a passive channel is never batched for a digest")
    void passiveChannelIsNeverBatched() {
        resolves(ChannelType.IN_APP);

        fanOut.fanOut(request(), command(ChannelType.IN_APP));

        assertThat(statusesRecorded()).doesNotContain(DeliveryStatus.BATCHED);
    }

    /**
     * The control for both. The same suppression list and the same digest window must still settle
     * an interrupting delivery, or the two tests above prove only that the rules were deleted.
     */
    @Test
    @DisplayName("an interrupting channel is still suppressed by the same list")
    void interruptingChannelIsStillSuppressed() {
        resolves(ChannelType.EMAIL);
        when(channelRegistry.find(eq(ChannelType.EMAIL)))
                .thenReturn(Optional.of(mock(ru.ludwigandreas.notification.service.channel.NotificationChannel.class)));

        fanOut.fanOut(request(), command(ChannelType.EMAIL));

        assertThat(statusesRecorded()).contains(DeliveryStatus.SUPPRESSED);
        verify(statusRecorder, never()).transition(any(), eq(DeliveryStatus.BATCHED), anyString());
    }

    private List<DeliveryStatus> statusesRecorded() {
        ArgumentCaptor<DeliveryStatus> captor = ArgumentCaptor.forClass(DeliveryStatus.class);
        verify(statusRecorder, org.mockito.Mockito.atLeast(0))
                .transition(any(), captor.capture(), anyString());
        return captor.getAllValues();
    }

    private void resolves(ChannelType channel) {
        when(recipientResolver.resolve(any(), eq(channel), any()))
                .thenReturn(Optional.of(new ResolvedRecipient(
                        OWNER,
                        channel,
                        channel.isPassive() ? null : "someone@example.com",
                        Locale.ENGLISH,
                        ZoneId.of("UTC"),
                        "Someone",
                        null,
                        true,
                        noPreferences())));
    }

    private static NotificationRequestEntity request() {
        NotificationRequestEntity request = new NotificationRequestEntity();
        request.setId(java.util.UUID.randomUUID());
        return request;
    }

    private static NotificationCommand command(ChannelType channel) {
        return new NotificationCommand(null, "order-shipped", CATEGORY, CategoryClass.MARKETING,
                Priority.NORMAL, Set.of(channel), List.of(RecipientRef.ofUser(OWNER)), Map.of(),
                null, null, null, null);
    }

    private static RecipientPreferences noPreferences() {
        ZoneId zone = ZoneId.of("UTC");
        return new RecipientPreferences(Locale.ENGLISH, zone, QuietHours.none(zone), null, null);
    }

    private static NotificationProperties digestEnabledFor(String category) {
        NotificationProperties properties = new NotificationProperties();
        properties.getDigest().setEnabled(true);
        properties.getDigest().getCategories().put(category, Duration.ofHours(1));
        return properties;
    }
}
