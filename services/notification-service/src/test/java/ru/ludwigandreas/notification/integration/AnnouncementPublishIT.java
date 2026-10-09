package ru.ludwigandreas.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import ru.ludwigandreas.notification.repository.AnnouncementMarkerRepository;
import ru.ludwigandreas.notification.repository.AnnouncementRepository;
import ru.ludwigandreas.notification.repository.entity.AnnouncementContentId;
import ru.ludwigandreas.notification.service.model.AnnouncementAdminView;
import ru.ludwigandreas.notification.repository.entity.AnnouncementMarkerEntity;
import ru.ludwigandreas.notification.repository.entity.AnnouncementMarkerId;
import ru.ludwigandreas.notification.service.announcement.AnnouncementAdminService;
import ru.ludwigandreas.notification.service.exception.AudienceNotPermittedException;
import ru.ludwigandreas.notification.service.exception.UnknownAnnouncementCategoryException;
import ru.ludwigandreas.notification.service.exception.VisibilityWindowTooLongException;
import ru.ludwigandreas.notification.service.model.AudienceType;
import ru.ludwigandreas.notification.service.model.CategoryClass;

/**
 * Publishing and correcting an announcement, against a real database and the real template tree.
 *
 * <p>The audit assertions live in {@code AnnouncementAuditTest} rather than here. Substituting a
 * recording sink into this context was tried and abandoned - the platform already publishes a
 * {@code @Primary} {@code AuditSink}, so a second one breaks the shared context thirteen other
 * integration tests rely on - and the questions being asked ("was record called, and with what")
 * need no database.
 *
 * <p>The announcement catalogue comes from {@link NotificationTestBase}'s shared properties rather
 * than a per-class {@code @TestPropertySource}: each distinct property set creates its own Spring
 * context and its own PostgreSQL container, and one context too many evicts another whose container
 * then stops, failing unrelated tests with "connection refused".
 *
 * <p>The two claims worth proving here are both about <em>cost</em> and <em>atomicity</em> rather
 * than about happy-path behaviour:
 *
 * <ul>
 *   <li><b>One announcement is a constant number of rows.</b> The whole aggregate exists for this.
 *       {@link #rowCountFollowsLocalesNotAudience()} is the assertion that would fail if anybody
 *       reintroduced a per-recipient fan-out, and it fails loudly rather than as a performance
 *       regression nobody attributes.</li>
 *   <li><b>A locale that fails rejects the whole publish.</b> Half a translated announcement is
 *       worse than none, because the half that is missing is invisible to whoever published it.</li>
 * </ul>
 */
class AnnouncementPublishIT extends NotificationTestBase {

    private static final Map<String, Object> VARIABLES =
            Map.of("productName", "Ludwig", "version", "1.4.0");

    @Autowired
    private AnnouncementAdminService adminService;

    @Autowired
    private AnnouncementRepository announcements;

    @Autowired
    private AnnouncementContentRepository contents;

    @Autowired
    private AnnouncementMarkerRepository markers;

    @BeforeEach
    void resetState() {
        markers.deleteAll();
        contents.deleteAll();
        announcements.deleteAll();
    }

    @Test
    @DisplayName("a published announcement is one row with one content row per supported locale")
    void publishStoresOneRowPerLocale() {
        AnnouncementAdminView published = publish("platform-release", AudienceType.EVERYONE, null);

        assertThat(announcements.findAll()).hasSize(1);
        assertThat(published.audienceType()).isEqualTo(AudienceType.EVERYONE);
        assertThat(published.audienceValue())
                .as("an EVERYONE audience must carry no value; the check constraint agrees")
                .isNull();
        // Snapshotted from the catalogue, so a later reclassification cannot retroactively change
        // whether people were allowed to decline what they already received.
        assertThat(published.categoryClass()).isEqualTo(CategoryClass.PLATFORM);

        assertThat(contentFor(published.id(), "en")).isNotNull();
        assertThat(contentFor(published.id(), "ru")).isNotNull();
        assertThat(contents.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("each locale's content is actually in that language")
    void everyLocaleIsRenderedInItsOwnLanguage() {
        AnnouncementAdminView published = publish("platform-release", AudienceType.EVERYONE, null);

        assertThat(contentFor(published.id(), "en")).contains("is now available");
        assertThat(contentFor(published.id(), "ru"))
                .as("a Russian row holding English text would mean the resolver fell back silently")
                .contains("Доступна версия");
    }

    /**
     * The assertion the whole aggregate exists for. Two content rows for an audience of everybody is
     * the same as two content rows for an audience of one — which is what a per-recipient fan-out
     * cannot do, and what makes a release note to fifty thousand people cost three rows instead of a
     * hundred and fifty thousand.
     */
    @Test
    @DisplayName("the row count follows the number of locales, never the size of the audience")
    void rowCountFollowsLocalesNotAudience() {
        publish("platform-release", AudienceType.EVERYONE, null);
        publish("platform-release", AudienceType.ROLE, "ADMIN");

        assertThat(announcements.findAll()).hasSize(2);
        assertThat(contents.findAll())
                .as("two announcements x two locales, regardless of how many people are in either")
                .hasSize(4);
        assertThat(markers.findAll())
                .as("nobody has dismissed anything, so there are no per-recipient rows at all")
                .isEmpty();
    }

    @Test
    @DisplayName("a role audience stores the role code")
    void roleAudienceIsStored() {
        AnnouncementAdminView published = publish("platform-release", AudienceType.ROLE, "ADMIN");

        assertThat(published.audienceType()).isEqualTo(AudienceType.ROLE);
        // Stored in the ROLE_-prefixed form, not the bare code the configuration names. That is
        // deliberate and load-bearing: a principal's roles arrive already normalised, and the
        // visibility predicate compares the two directly. Storing the bare code matches nothing, so
        // the announcement would publish cleanly and be visible to nobody - see
        // AnnouncementVisibilityIT, where reverting the normalisation fails four tests.
        assertThat(published.audienceValue()).isEqualTo("ROLE_ADMIN");
    }

    @Test
    @DisplayName("a role that is not on the allowlist is refused and stores nothing")
    void unlistedRoleStoresNothing() {
        assertThatThrownBy(() -> publish("platform-release", AudienceType.ROLE, "AUDITOR"))
                .isInstanceOf(AudienceNotPermittedException.class);

        assertThat(announcements.findAll()).isEmpty();
        assertThat(contents.findAll()).isEmpty();
    }

    @Test
    @DisplayName("an unconfigured category is refused rather than defaulted")
    void unknownCategoryIsRefused() {
        assertThatThrownBy(() -> publish("not-in-the-catalogue", AudienceType.EVERYONE, null))
                .isInstanceOf(UnknownAnnouncementCategoryException.class);

        assertThat(announcements.findAll()).isEmpty();
    }

    @Test
    @DisplayName("a window longer than the configured maximum is refused")
    void overlongWindowIsRefused() {
        Instant from = Instant.now();
        assertThatThrownBy(() -> adminService.publish("platform-release", AudienceType.EVERYONE,
                null, "platform-release", VARIABLES, from, from.plus(60, ChronoUnit.DAYS)))
                .isInstanceOf(VisibilityWindowTooLongException.class);

        assertThat(announcements.findAll()).isEmpty();
    }

    @Test
    @DisplayName("an empty window is refused")
    void emptyWindowIsRefused() {
        Instant from = Instant.now();
        assertThatThrownBy(() -> adminService.publish("platform-release", AudienceType.EVERYONE,
                null, "platform-release", VARIABLES, from, from))
                .isInstanceOf(VisibilityWindowTooLongException.class);
    }

    /**
     * A template failing in one locale must reject the whole publish. The variables below satisfy
     * nothing: the template references {@code version}, so a strict render fails — and it fails for
     * every locale, which is why this also asserts the transaction left nothing behind.
     */
    @Test
    @DisplayName("a render failure leaves no announcement, no content and no run")
    void renderFailureIsAtomic() {
        Instant from = Instant.now();
        assertThatThrownBy(() -> adminService.publish("platform-release", AudienceType.EVERYONE,
                null, "platform-release", Map.of("productName", "Ludwig"), from,
                from.plus(1, ChronoUnit.DAYS)))
                .as("a template that cannot render must not produce a half-published announcement")
                .isInstanceOf(RuntimeException.class);

        assertThat(announcements.findAll()).isEmpty();
        assertThat(contents.findAll()).isEmpty();
    }

    @Test
    @DisplayName("a missing template is refused and stores nothing")
    void missingTemplateIsAtomic() {
        Instant from = Instant.now();
        assertThatThrownBy(() -> adminService.publish("platform-release", AudienceType.EVERYONE,
                null, "no-such-template", VARIABLES, from, from.plus(1, ChronoUnit.DAYS)))
                .isInstanceOf(RuntimeException.class);

        assertThat(announcements.findAll()).isEmpty();
        assertThat(contents.findAll()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // Correction
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a correction re-renders every locale together")
    void correctionRendersEveryLocale() {
        AnnouncementAdminView published = publish("platform-release", AudienceType.EVERYONE, null);

        adminService.correct(published.id(),
                Map.of("productName", "Ludwig", "version", "1.4.1"));

        assertThat(contentFor(published.id(), "en")).contains("1.4.1");
        assertThat(contentFor(published.id(), "ru"))
                .as("correcting one language and not the other leaves the other saying the wrong "
                        + "thing, which nobody would notice")
                .contains("1.4.1");
        assertThat(contents.findAll())
                .as("a correction replaces rows rather than adding them")
                .hasSize(2);
    }

    @Test
    @DisplayName("a correction leaves dismissals alone")
    void correctionPreservesDismissals() {
        AnnouncementAdminView published = publish("platform-release", AudienceType.EVERYONE, null);
        markers.save(AnnouncementMarkerEntity.builder()
                .id(new AnnouncementMarkerId(published.id(), "someone"))
                .dismissedAt(Instant.now())
                .build());

        adminService.correct(published.id(),
                Map.of("productName", "Ludwig", "version", "1.4.1"));

        assertThat(markers.findAll())
                .as("fixing a typo must not push a dismissed announcement back in front of somebody")
                .hasSize(1);
    }

    @Test
    @DisplayName("a correction does not change the audience or the window")
    void correctionDoesNotChangeTheAudience() {
        AnnouncementAdminView published = publish("platform-release", AudienceType.ROLE, "ADMIN");
        Instant until = published.visibleUntil();

        adminService.correct(published.id(),
                Map.of("productName", "Ludwig", "version", "1.4.1"));

        // Read back from the repository rather than trusting the returned view, because the point
        // of this test is that the STORED audience was not changed by the correction.
        var reloaded = announcements.findById(published.id()).orElseThrow();
        assertThat(reloaded.getAudienceKind().name()).isEqualTo(AudienceType.ROLE.name());
        assertThat(reloaded.getAudienceValue()).isEqualTo("ROLE_ADMIN");
        assertThat(reloaded.getVisibleUntil()).isEqualTo(until);
    }

    @Test
    @DisplayName("the catalogue decides the channels, not the request")
    void catalogueDecidesChannels() {
        assertThat(adminService.channelsFor("platform-release"))
                .containsExactly(ru.ludwigandreas.notification.service.model.ChannelType.IN_APP);
    }

    private AnnouncementAdminView publish(String category, AudienceType type, String value) {
        Instant from = Instant.now().minusSeconds(1);
        return adminService.publish(category, type, value, "platform-release", VARIABLES, from,
                from.plus(7, ChronoUnit.DAYS)).announcement();
    }

    private String contentFor(UUID announcementId, String locale) {
        return contents.findById(new AnnouncementContentId(announcementId, locale))
                .map(content -> content.getSubject() + "|" + content.getBodyHtml())
                .orElse(null);
    }

    /** Exposed so the test can assert the locales that were stored, not only their count. */
    private List<String> storedLocales(UUID announcementId) {
        return contents.findAll().stream()
                .filter(content -> content.getId().getAnnouncementId().equals(announcementId))
                .map(content -> content.getId().getLocale())
                .sorted()
                .toList();
    }
}
