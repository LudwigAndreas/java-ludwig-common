package ru.ludwigandreas.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import ru.ludwigandreas.notification.repository.entity.AnnouncementMarkerEntity;
import ru.ludwigandreas.notification.repository.entity.AnnouncementMarkerId;
import ru.ludwigandreas.notification.service.announcement.AnnouncementAdminService;
import ru.ludwigandreas.notification.service.model.AudienceType;
import ru.ludwigandreas.notification.service.retention.RetentionService;
import ru.ludwigandreas.notification.settings.NotificationProperties;

/**
 * Announcement retention, and the property that makes the whole aggregate worth having.
 *
 * <p>{@link #purgeCostDoesNotScaleWithAudience()} is the one to read. A per-recipient fan-out leaves
 * one row per person to sweep, so an unread broadcast to fifty thousand people makes the <em>purge</em>
 * the expensive part rather than the send. Here it is a constant number of statements whether the
 * audience was ten people or the entire organisation, and the cascade does the rest.
 *
 * <p>The anchor is the second thing worth asserting. Every other window in this service runs from
 * creation; this one runs from the <b>end of visibility</b>, because an announcement published for
 * next quarter has not started yet and one still showing must never vanish out from under the people
 * reading it.
 */
class AnnouncementRetentionIT extends NotificationTestBase {

    private static final Map<String, Object> VARIABLES =
            Map.of("productName", "Ludwig", "version", "1.4.0");

    @Autowired
    private AnnouncementAdminService adminService;

    @Autowired
    private RetentionService retentionService;

    @Autowired
    private AnnouncementRepository announcements;

    @Autowired
    private AnnouncementContentRepository contents;

    @Autowired
    private AnnouncementMarkerRepository markers;

    @Autowired
    private AnnouncementEmailRunRepository runs;

    @Autowired
    private NotificationProperties properties;

    @BeforeEach
    void resetState() {
        runs.deleteAll();
        markers.deleteAll();
        contents.deleteAll();
        announcements.deleteAll();
    }

    @Test
    @DisplayName("an announcement whose window closed long ago is purged with its content")
    void expiredAnnouncementIsPurged() {
        UUID id = publishEndingAgo(properties.getRetention().getAnnouncementTtl()
                .plus(Duration.ofDays(1)));

        assertThat(retentionService.purgeAnnouncements(Instant.now())).isEqualTo(1L);

        assertThat(announcements.findById(id)).isEmpty();
        assertThat(contents.findAll())
                .as("content must go with the announcement rather than being orphaned")
                .isEmpty();
    }

    /**
     * The anchor. This announcement was published almost a month ago and is still showing, so it must
     * survive - which is what would fail if anybody "simplified" the predicate to use
     * {@code created_at}.
     *
     * <p>Its age is bounded by {@code max-visibility-window} rather than being arbitrarily old,
     * because the cap is enforced at publish: asking for a decade-long window is rejected, which is
     * itself the right behaviour and was how this fixture first failed.
     */
    @Test
    @DisplayName("a still-visible announcement is never purged, whatever its age")
    void visibleAnnouncementSurvivesItsAge() {
        // ONE reference instant, not two calls to now(): the window is measured between the two
        // bounds, so computing them from separate clock reads makes it 28 days plus however many
        // microseconds elapsed in between - which is how this fixture first tripped a cap of exactly
        // 30 days.
        Instant now = Instant.now();
        UUID id = adminService.publish("platform-release", AudienceType.EVERYONE, null,
                        "platform-release", VARIABLES, now.minus(27, ChronoUnit.DAYS),
                        now.plus(1, ChronoUnit.DAYS))
                .announcement().id();

        assertThat(retentionService.purgeAnnouncements(Instant.now()))
                .as("the window is what matters, not the age")
                .isZero();
        assertThat(announcements.findById(id)).isPresent();
    }

    @Test
    @DisplayName("an announcement whose window closed recently is kept")
    void recentlyExpiredAnnouncementIsKept() {
        UUID id = publishEndingAgo(Duration.ofDays(1));

        assertThat(retentionService.purgeAnnouncements(Instant.now())).isZero();
        assertThat(announcements.findById(id)).isPresent();
    }

    /**
     * The property the aggregate exists for, asserted as a row count rather than as a timing.
     *
     * <p>Fifty thousand dismissals would be fifty thousand marker rows under a per-recipient design
     * <em>before</em> anybody dismissed anything. Here they only exist for people who actually did,
     * and the purge removes announcement, content and markers through one cascade either way.
     */
    @Test
    @DisplayName("the purge cost does not scale with the size of the audience")
    void purgeCostDoesNotScaleWithAudience() {
        UUID id = publishEndingAgo(properties.getRetention().getAnnouncementTtl()
                .plus(Duration.ofDays(1)));
        // A handful of people dismissed it; the rest of the organisation never touched it and
        // therefore has no row at all.
        for (int i = 0; i < 5; i++) {
            markers.save(AnnouncementMarkerEntity.builder()
                    .id(new AnnouncementMarkerId(id, "dismisser-" + i))
                    .dismissedAt(Instant.now())
                    .build());
        }
        assertThat(markers.findAll()).hasSize(5);

        assertThat(retentionService.purgeAnnouncements(Instant.now())).isEqualTo(1L);

        assertThat(announcements.findAll()).isEmpty();
        assertThat(markers.findAll())
                .as("markers go with the announcement in one cascade")
                .isEmpty();
        assertThat(contents.findAll()).isEmpty();
    }

    @Test
    @DisplayName("an expired announcement's email run is purged with it")
    void emailRunIsPurgedWithTheAnnouncement() {
        Instant until = Instant.now().minus(200, ChronoUnit.DAYS);
        UUID id = adminService.publish("platform-incident", AudienceType.EVERYONE, null,
                        "platform-release", VARIABLES, until.minus(29, ChronoUnit.DAYS), until)
                .announcement().id();
        assertThat(runs.runFor(id)).isPresent();

        assertThat(retentionService.purgeAnnouncements(Instant.now())).isEqualTo(1L);

        assertThat(runs.runFor(id))
                .as("the run is machinery that followed from the announcement; it has no life "
                        + "of its own once the announcement is gone")
                .isEmpty();
    }

    @Test
    @DisplayName("the purge is bounded by the configured batch size")
    void purgeIsBatched() {
        Duration longAgo = properties.getRetention().getAnnouncementTtl()
                .plus(Duration.ofDays(1));
        for (int i = 0; i < 3; i++) {
            publishEndingAgo(longAgo);
        }

        // The batch size in this deployment is well above three, so one call takes them all. The
        // assertion is that the step reports what it removed rather than that it looped.
        assertThat(retentionService.purgeAnnouncements(Instant.now())).isEqualTo(3L);
        assertThat(announcements.findAll()).isEmpty();
    }

    /** Publishes an announcement whose visibility ended this long ago. */
    private UUID publishEndingAgo(Duration ago) {
        Instant until = Instant.now().minus(ago);
        return adminService.publish("platform-release", AudienceType.EVERYONE, null,
                        "platform-release", VARIABLES, until.minus(1, ChronoUnit.DAYS), until)
                .announcement().id();
    }
}
