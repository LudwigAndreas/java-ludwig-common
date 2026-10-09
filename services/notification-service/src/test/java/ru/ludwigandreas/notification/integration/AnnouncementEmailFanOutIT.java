package ru.ludwigandreas.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.ludwigandreas.notification.repository.AnnouncementContentRepository;
import ru.ludwigandreas.notification.repository.AnnouncementEmailRunRepository;
import ru.ludwigandreas.notification.repository.AnnouncementMarkerRepository;
import ru.ludwigandreas.notification.repository.AnnouncementRepository;
import ru.ludwigandreas.notification.repository.DeliveryStatusHistoryRepository;
import ru.ludwigandreas.notification.repository.NotificationDeliveryRepository;
import ru.ludwigandreas.notification.repository.NotificationRequestRepository;
import ru.ludwigandreas.notification.repository.SuppressionRepository;
import ru.ludwigandreas.notification.service.model.BroadcastRunView;
import ru.ludwigandreas.notification.repository.entity.ChannelKind;
import ru.ludwigandreas.notification.repository.entity.DeliveryStatus;
import ru.ludwigandreas.notification.repository.entity.NotificationDeliveryEntity;
import ru.ludwigandreas.notification.service.announcement.AnnouncementAdminService;
import ru.ludwigandreas.notification.service.announcement.AnnouncementBroadcastScheduler;
import ru.ludwigandreas.notification.service.announcement.AnnouncementEmailBroadcastService;
import ru.ludwigandreas.notification.service.model.AudienceType;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.outbox.repository.OutboxMessageRepository;

/**
 * The email half of an announcement: the batched, resumable walk that turns one announcement into N
 * ordinary deliveries.
 *
 * <p>Three claims are worth more than the happy path here, and each has its own test:
 *
 * <ul>
 *   <li><b>The announcement is visible before any email exists.</b> The inbox half is one row in the
 *       publish transaction; the email half is a run somebody else picks up. A design that did both
 *       inline would put a hundred thousand inserts in the request that accepts the
 *       announcement.</li>
 *   <li><b>A resumed run neither skips nor repeats.</b> Deliveries and the cursor commit together, so
 *       there is no partial batch to reconcile - and the existing unique dedup key is the backstop if
 *       two replicas ever raced.</li>
 *   <li><b>A broadcast is not a bypass.</b> Suppression, missing addresses and quiet hours all still
 *       apply per recipient, because each batch goes through the ordinary ingress rather than a second
 *       send path.</li>
 * </ul>
 */
class AnnouncementEmailFanOutIT extends NotificationTestBase {

    private static final Map<String, Object> VARIABLES =
            Map.of("productName", "Ludwig", "version", "1.4.0");

    @Autowired
    private AnnouncementAdminService adminService;

    @Autowired
    private AnnouncementEmailBroadcastService broadcastService;

    @Autowired
    private AnnouncementBroadcastScheduler scheduler;

    @Autowired
    private AnnouncementRepository announcements;

    @Autowired
    private AnnouncementContentRepository contents;

    @Autowired
    private AnnouncementMarkerRepository markers;

    @Autowired
    private AnnouncementEmailRunRepository runs;

    @Autowired
    private NotificationDeliveryRepository deliveries;

    @Autowired
    private NotificationRequestRepository requests;

    @Autowired
    private DeliveryStatusHistoryRepository history;

    @Autowired
    private SuppressionRepository suppressions;

    @Autowired
    private ru.ludwigandreas.notification.service.preference.SuppressionService suppressionService;

    @Autowired
    private OutboxMessageRepository outbox;

    @Autowired
    private RecipientFixtures recipients;

    @BeforeEach
    void resetState() {
        runs.deleteAll();
        markers.deleteAll();
        contents.deleteAll();
        announcements.deleteAll();
        history.deleteAll();
        deliveries.deleteAll();
        requests.deleteAll();
        suppressions.deleteAll();
        outbox.deleteAll();
        recipients.reset();
    }

    // ---------------------------------------------------------------------------------------------
    // A run exists only where the category emails
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an inbox-only category starts no run")
    void inboxOnlyCategoryStartsNoRun() {
        UUID id = publish("platform-release", AudienceType.EVERYONE, null);

        assertThat(broadcastService.runFor(id))
                .as("there is no operation to poll, so the publish must not name one")
                .isEmpty();
    }

    @Test
    @DisplayName("an emailing category starts a run, and the announcement is already visible")
    void emailingCategoryStartsARun() {
        givenUsers(3);
        UUID id = publish("platform-incident", AudienceType.EVERYONE, null);

        assertThat(announcements.findById(id))
                .as("the inbox half is complete before the email half has started")
                .isPresent();
        BroadcastRunView run = broadcastService.runFor(id).orElseThrow();
        assertThat(run.status().name()).isEqualTo("PENDING");
        assertThat(deliveries.findAll())
                .as("nothing is sent in the publish transaction")
                .isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // The walk
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the fan-out creates one email delivery per active recipient")
    void fanOutReachesEveryone() {
        givenUsers(7);
        UUID id = publish("platform-incident", AudienceType.EVERYONE, null);

        drain();

        assertThat(emailDeliveries()).hasSize(7);
        BroadcastRunView run = broadcastService.runFor(id).orElseThrow();
        assertThat(run.status().name()).isEqualTo("SUCCEEDED");
        assertThat(run.deliveriesCreated()).isEqualTo(7);
        assertThat(run.finishedAt()).isNotNull();
    }

    /**
     * A deactivated account is never paged, let alone emailed. The same rule recipient resolution
     * already applies per delivery, applied one level earlier so a leaver does not even appear in the
     * audience.
     */
    @Test
    @DisplayName("deactivated accounts are not in the audience")
    void deactivatedAccountsAreExcluded() {
        givenUsers(3);
        recipients.givenDeactivatedUser("leaver", "leaver@example.com");

        publish("platform-incident", AudienceType.EVERYONE, null);
        drain();

        assertThat(emailDeliveries()).hasSize(3);
        assertThat(emailDeliveries())
                .extracting(NotificationDeliveryEntity::getRecipientUserId)
                .doesNotContain("leaver");
    }

    @Test
    @DisplayName("a role audience reaches only that role's holders")
    void roleAudienceReachesOnlyThatRole() {
        recipients.givenUserWithRoles("admin-1", "admin1@example.com", "ADMIN");
        recipients.givenUserWithRoles("admin-2", "admin2@example.com", "ADMIN");
        recipients.givenUserWithRoles("support-1", "support1@example.com", "SUPPORT");

        publish("platform-incident", AudienceType.ROLE, "ADMIN");
        drain();

        assertThat(emailDeliveries())
                .extracting(NotificationDeliveryEntity::getRecipientUserId)
                .containsExactlyInAnyOrder("admin-1", "admin-2");
    }

    /**
     * The resumability claim. The run is advanced one batch at a time and interrupted in the middle;
     * the total must come out exactly right, with no recipient served twice.
     */
    @Test
    @DisplayName("a run interrupted part way resumes without skipping or repeating anybody")
    void interruptedRunResumesExactly() {
        givenUsers(12);
        UUID id = publish("platform-incident", AudienceType.EVERYONE, null);
        UUID runId = broadcastService.runFor(id).orElseThrow().id();

        // One batch by hand, then stop - as a lost lease or a restart would. The batch size is 500
        // here, so twelve recipients fit in one batch and the run is not yet finished: the cursor has
        // advanced but nothing has told it the audience is exhausted.
        assertThat(broadcastService.advanceOneBatch(runId))
                .as("more to do: the run does not know it has reached the end until a batch is empty")
                .isTrue();
        assertThat(emailDeliveries()).hasSize(12);

        // Resume. The cursor is past the end, so the next batch finds nobody and finishes.
        drain();

        assertThat(emailDeliveries())
                .as("exactly the audience, with nobody twice")
                .hasSize(12);
        assertThat(emailDeliveries())
                .extracting(NotificationDeliveryEntity::getRecipientUserId)
                .doesNotHaveDuplicates();
        assertThat(broadcastService.runFor(id).orElseThrow().deliveriesCreated()).isEqualTo(12);
    }

    /**
     * The backstop, exercised directly: re-running a batch whose cursor was not advanced produces the
     * same idempotency key and therefore the same per-delivery dedup keys, and the unique index
     * refuses the duplicates rather than the run failing.
     */
    @Test
    @DisplayName("re-running the same batch creates no second delivery for anybody")
    void rerunningABatchIsHarmless() {
        givenUsers(4);
        UUID id = publish("platform-incident", AudienceType.EVERYONE, null);
        UUID runId = broadcastService.runFor(id).orElseThrow().id();

        broadcastService.advanceOneBatch(runId);
        long firstPass = emailDeliveries().size();

        // Rewind the cursor, as a replica that lost its commit would have left it, and advance again.
        ru.ludwigandreas.notification.repository.entity.AnnouncementEmailRunEntity stored =
                runs.findById(runId).orElseThrow();
        stored.setCursorSubject(null);
        runs.save(stored);
        broadcastService.advanceOneBatch(runId);

        assertThat(emailDeliveries())
                .as("the unique dedup key makes the overlap harmless rather than a double send")
                .hasSize((int) firstPass);
    }

    // ---------------------------------------------------------------------------------------------
    // A broadcast is not a bypass
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a suppressed address is suppressed in a broadcast too")
    void suppressionStillApplies() {
        givenUsers(3);
        // Through the service rather than by inserting a row, so the address is normalised the same
        // way the suppression check will normalise it when the fan-out asks.
        suppressionService.suppress(ChannelType.EMAIL, "user-1@example.com", "hard-bounce", null,
                null);

        publish("platform-incident", AudienceType.EVERYONE, null);
        drain();

        assertThat(emailDeliveries())
                .filteredOn(d -> d.getStatus() == DeliveryStatus.SUPPRESSED)
                .as("a bounce is a fact about the address; a broadcast does not override it")
                .hasSize(1);
    }

    @Test
    @DisplayName("a recipient with no verified address is one terminal delivery and does not stop the run")
    void unreachableRecipientDoesNotStopTheRun() {
        givenUsers(2);
        recipients.givenUserWithUnverifiedEmail("unverified", "unverified@example.com");

        UUID id = publish("platform-incident", AudienceType.EVERYONE, null);
        drain();

        assertThat(emailDeliveries())
                .filteredOn(d -> d.getStatus() == DeliveryStatus.DEAD)
                .hasSize(1);
        assertThat(broadcastService.runFor(id).orElseThrow().status().name())
                .as("one unreachable person must not fail the whole broadcast")
                .isEqualTo("SUCCEEDED");
    }

    /**
     * A broadcast runs at {@code BULK} priority, so it cannot jump the queue ahead of a password
     * reset. The priority lanes exist for exactly this.
     */
    @Test
    @DisplayName("broadcast deliveries are BULK priority")
    void broadcastIsBulkPriority() {
        givenUsers(2);
        publish("platform-incident", AudienceType.EVERYONE, null);
        drain();

        assertThat(emailDeliveries())
                .allSatisfy(d -> assertThat(d.getPriority().name()).isEqualTo("BULK"));
    }

    // ---------------------------------------------------------------------------------------------
    // Cancellation
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a cancelled run stops creating deliveries and keeps the ones it made")
    void cancellationIsCooperativeAndKeepsCommittedWork() {
        givenUsers(5);
        UUID id = publish("platform-incident", AudienceType.EVERYONE, null);
        UUID runId = broadcastService.runFor(id).orElseThrow().id();

        broadcastService.advanceOneBatch(runId);
        long created = emailDeliveries().size();
        assertThat(created).isPositive();

        broadcastService.requestCancellation(id);
        drain();

        BroadcastRunView run = broadcastService.runFor(id).orElseThrow();
        assertThat(run.status().name()).isEqualTo("CANCELLED");
        assertThat(run.finishedAt()).isNotNull();
        assertThat(emailDeliveries())
                .as("already-created deliveries are committed work and are NOT recalled")
                .hasSize((int) created);
    }

    @Test
    @DisplayName("cancelling an already-finished run returns it rather than failing")
    void cancellingAFinishedRunIsNotAnError() {
        givenUsers(2);
        UUID id = publish("platform-incident", AudienceType.EVERYONE, null);
        drain();
        assertThat(broadcastService.runFor(id).orElseThrow().status().name())
                .isEqualTo("SUCCEEDED");

        BroadcastRunView run = broadcastService.requestCancellation(id);

        assertThat(run.status().name())
                .as("a finished run is returned as it stands, not retroactively cancelled")
                .isEqualTo("SUCCEEDED");
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** Advances every run until none reports more to do. */
    private void drain() {
        for (int cycle = 0; cycle < 10; cycle++) {
            scheduler.runOneCycleForTesting();
            if (runs.findClaimable(10).isEmpty()) {
                return;
            }
        }
        throw new AssertionError("the fan-out did not finish within ten cycles");
    }

    private List<NotificationDeliveryEntity> emailDeliveries() {
        return deliveries.findAll().stream()
                .filter(d -> d.getChannel() == ChannelKind.EMAIL)
                .toList();
    }

    private void givenUsers(int count) {
        for (int i = 1; i <= count; i++) {
            recipients.givenUser("user-" + i, "user-" + i + "@example.com");
        }
    }

    private UUID publish(String category, AudienceType type, String value) {
        Instant from = Instant.now().minusSeconds(1);
        return adminService.publish(category, type, value, "platform-release", VARIABLES, from,
                from.plus(7, ChronoUnit.DAYS)).announcement().id();
    }

}
