package ru.ludwigandreas.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.ludwigandreas.notification.repository.AnnouncementContentRepository;
import ru.ludwigandreas.notification.repository.AnnouncementRepository;
import ru.ludwigandreas.notification.repository.entity.AnnouncementEntity;
import ru.ludwigandreas.notification.repository.entity.AudienceKind;
import ru.ludwigandreas.notification.repository.entity.CategoryKind;
import ru.ludwigandreas.notification.service.announcement.AnnouncementAdminService;
import ru.ludwigandreas.notification.service.announcement.AnnouncementAudienceResolver;
import ru.ludwigandreas.notification.service.announcement.AnnouncementEmailBroadcastService;
import ru.ludwigandreas.notification.service.exception.AnnouncementWindowOpenedException;
import ru.ludwigandreas.notification.service.exception.AnnouncementWithdrawnException;
import ru.ludwigandreas.notification.service.exception.InvalidVisibilityWindowException;
import ru.ludwigandreas.notification.service.exception.VisibilityWindowTooLongException;
import ru.ludwigandreas.notification.service.model.AnnouncementAdminView;
import ru.ludwigandreas.notification.service.model.BroadcastRunView;
import ru.ludwigandreas.notification.service.template.TemplateRenderer;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.testsupport.security.TestPrincipalBuilder;
import ru.ludwigandreas.webcore.config.WebCoreProperties;

/**
 * Withdrawal and window changes, as far as what they audit and what they refuse.
 *
 * <p>A unit test for the same reason as {@link AnnouncementAuditTest}: the integration context's
 * {@code @Primary} sink cannot be substituted, and "was exactly one record written, or none" needs a
 * sink that remembers. What the endpoints answer is {@code AnnouncementLifecycleIT}'s job.
 */
class AnnouncementLifecycleTest {

    private static final UUID ID = UUID.fromString("0b6f2c1e-4d3a-4e5f-8a7b-9c0d1e2f3a4b");

    private final RecordingAuditSink sink = new RecordingAuditSink();
    private AnnouncementRepository announcements;
    private AnnouncementEmailBroadcastService broadcastService;
    private AnnouncementAdminService service;

    @BeforeEach
    void setUp() {
        announcements = mock(AnnouncementRepository.class);
        broadcastService = mock(AnnouncementEmailBroadcastService.class);
        NotificationProperties properties = new NotificationProperties();
        properties.getAnnouncements().setMaxVisibilityWindow(Duration.ofDays(30));

        service = new AnnouncementAdminService(announcements,
                mock(AnnouncementContentRepository.class), mock(AnnouncementAudienceResolver.class),
                mock(TemplateRenderer.class), properties, new WebCoreProperties(), sink,
                broadcastService);
        when(broadcastService.runFor(ID)).thenReturn(Optional.empty());

        SecurityContextHolder.getContext().setAuthentication(
                TestPrincipalBuilder.user("announcer").roles("ROLE_NOTIFICATION_ANNOUNCER")
                        .authentication());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------------------------------------
    // Withdrawal
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a withdrawal is recorded on the announcement and audited once, with its reason")
    void withdrawalIsRecordedAndAudited() {
        AnnouncementEntity announcement = stored(open());

        AnnouncementAdminView view = service.withdraw(ID, "wrong audience");

        assertThat(view.withdrawnAt()).isNotNull();
        assertThat(view.withdrawnBy()).isEqualTo("announcer");
        assertThat(view.withdrawalReason()).isEqualTo("wrong audience");
        assertThat(announcement.getVisibleUntil())
                .as("the original window is kept as part of the record").isNotNull();
        assertThat(sink.attributesOf("config.announcement-withdrawn"))
                .containsEntry("reason", "wrong audience");
    }

    @Test
    @DisplayName("withdrawing twice keeps the first withdrawal and writes no second record")
    void repeatWithdrawalIsIdempotent() {
        AnnouncementEntity announcement = open();
        Instant first = Instant.now().minus(1, ChronoUnit.HOURS);
        announcement.setWithdrawnAt(first);
        announcement.setWithdrawnBy("first-announcer");
        stored(announcement);

        AnnouncementAdminView view = service.withdraw(ID, "again");

        assertThat(view.withdrawnAt()).isEqualTo(first);
        assertThat(view.withdrawnBy()).isEqualTo("first-announcer");
        assertThat(sink.actions()).isEmpty();
        verify(broadcastService, never()).requestCancellation(ID);
    }

    @Test
    @DisplayName("a withdrawal cancels the email fan-out when there is one")
    void withdrawalCancelsTheFanOut() {
        stored(open());
        when(broadcastService.runFor(ID)).thenReturn(Optional.of(mock(BroadcastRunView.class)));

        service.withdraw(ID, null);

        verify(broadcastService).requestCancellation(ID);
    }

    @Test
    @DisplayName("an inbox-only announcement is withdrawn without touching any email run")
    void inboxOnlyWithdrawalTouchesNoRun() {
        stored(open());

        service.withdraw(ID, null);

        verify(broadcastService, never()).requestCancellation(ID);
        assertThat(sink.actions()).containsExactly("config.announcement-withdrawn");
    }

    // ---------------------------------------------------------------------------------------------
    // Window changes
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("moving the end of an open window is applied and audited with both windows")
    void endOfOpenWindowMoves() {
        AnnouncementEntity announcement = stored(open());
        Instant from = announcement.getVisibleFrom();
        Instant previousUntil = announcement.getVisibleUntil();
        Instant until = Instant.now().plus(10, ChronoUnit.DAYS);

        service.changeWindow(ID, null, until);

        assertThat(announcement.getVisibleUntil()).isEqualTo(until);
        assertThat(announcement.getVisibleFrom()).isEqualTo(from);
        assertThat(sink.attributesOf("config.announcement-window-changed"))
                .containsEntry("previousVisibleUntil", previousUntil.toString())
                .containsEntry("visibleUntil", until.toString())
                .containsEntry("visibleFrom", from.toString());
    }

    @Test
    @DisplayName("the start of a window that has not opened can move")
    void startOfFutureWindowMoves() {
        Instant now = Instant.now();
        AnnouncementEntity announcement = stored(window(now.plus(2, ChronoUnit.DAYS),
                now.plus(5, ChronoUnit.DAYS)));
        Instant from = now.plus(1, ChronoUnit.DAYS);

        service.changeWindow(ID, from, now.plus(5, ChronoUnit.DAYS));

        assertThat(announcement.getVisibleFrom()).isEqualTo(from);
    }

    @Test
    @DisplayName("the start of an open window cannot move")
    void startOfOpenWindowIsFixed() {
        stored(open());

        assertThatThrownBy(() -> service.changeWindow(ID, Instant.now().plus(1, ChronoUnit.DAYS),
                Instant.now().plus(5, ChronoUnit.DAYS)))
                .isInstanceOf(AnnouncementWindowOpenedException.class);
        assertThat(sink.actions()).isEmpty();
    }

    @Test
    @DisplayName("an end in the past is refused; taking an announcement down now is a withdrawal")
    void endInThePastIsRefused() {
        stored(open());

        assertThatThrownBy(() -> service.changeWindow(ID, null, Instant.now().minusSeconds(1)))
                .isInstanceOf(InvalidVisibilityWindowException.class);
        assertThat(sink.actions()).isEmpty();
    }

    @Test
    @DisplayName("a not-yet-open start cannot be moved into the past")
    void futureStartCannotMoveIntoThePast() {
        Instant now = Instant.now();
        stored(window(now.plus(1, ChronoUnit.DAYS), now.plus(2, ChronoUnit.DAYS)));

        assertThatThrownBy(() -> service.changeWindow(ID, now.minus(1, ChronoUnit.DAYS),
                now.plus(2, ChronoUnit.DAYS)))
                .isInstanceOf(InvalidVisibilityWindowException.class);
    }

    @Test
    @DisplayName("a changed window longer than the maximum is refused")
    void windowOverTheMaximumIsRefused() {
        stored(open());

        assertThatThrownBy(() -> service.changeWindow(ID, null,
                Instant.now().plus(60, ChronoUnit.DAYS)))
                .isInstanceOf(VisibilityWindowTooLongException.class);
        assertThat(sink.actions()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // Finality
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a withdrawn announcement's window and content cannot change")
    void withdrawnIsFinal() {
        AnnouncementEntity announcement = open();
        announcement.setWithdrawnAt(Instant.now());
        stored(announcement);
        Instant until = announcement.getVisibleUntil();

        assertThatThrownBy(() -> service.changeWindow(ID, null,
                Instant.now().plus(5, ChronoUnit.DAYS)))
                .isInstanceOf(AnnouncementWithdrawnException.class);
        assertThatThrownBy(() -> service.correct(ID, Map.of()))
                .isInstanceOf(AnnouncementWithdrawnException.class);
        assertThat(announcement.getVisibleUntil()).isEqualTo(until);
        assertThat(sink.actions()).isEmpty();
    }

    private AnnouncementEntity stored(AnnouncementEntity announcement) {
        when(announcements.findForAdministration(ID)).thenReturn(Optional.of(announcement));
        return announcement;
    }

    private static AnnouncementEntity open() {
        Instant now = Instant.now();
        return window(now.minus(1, ChronoUnit.DAYS), now.plus(3, ChronoUnit.DAYS));
    }

    private static AnnouncementEntity window(Instant from, Instant until) {
        AnnouncementEntity announcement = AnnouncementEntity.builder()
                .category("platform-release")
                .categoryClass(CategoryKind.PLATFORM)
                .templateKey("platform-release")
                .audienceKind(AudienceKind.EVERYONE)
                .visibleFrom(from)
                .visibleUntil(until)
                .build();
        announcement.setId(ID);
        announcement.setCreatedAt(from);
        return announcement;
    }
}
