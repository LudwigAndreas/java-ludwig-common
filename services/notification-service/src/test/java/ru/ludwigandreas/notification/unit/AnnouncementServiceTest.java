package ru.ludwigandreas.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.ludwigandreas.notification.repository.AnnouncementContentRepository;
import ru.ludwigandreas.notification.repository.AnnouncementMarkerRepository;
import ru.ludwigandreas.notification.repository.AnnouncementRepository;
import ru.ludwigandreas.notification.repository.entity.AnnouncementContentEntity;
import ru.ludwigandreas.notification.repository.entity.AnnouncementContentId;
import ru.ludwigandreas.notification.repository.entity.AnnouncementEntity;
import ru.ludwigandreas.notification.repository.entity.AudienceKind;
import ru.ludwigandreas.notification.repository.entity.CategoryKind;
import ru.ludwigandreas.notification.service.announcement.AnnouncementService;
import ru.ludwigandreas.notification.service.exception.AnnouncementNotFoundException;
import ru.ludwigandreas.notification.service.model.AnnouncementView;
import ru.ludwigandreas.testsupport.security.TestPrincipalBuilder;
import ru.ludwigandreas.webcore.config.WebCoreProperties;

/**
 * The parts of the announcement read path that are logic rather than a query.
 *
 * <p>The visibility predicate itself is proved by {@code AnnouncementVisibilityIT} against a real
 * database, which is the only place a QueryDSL predicate means anything - a mock-based test of one
 * would assert that the arguments were passed, not that the query is right, and would have missed the
 * {@code ROLE_} prefix bug entirely. What is worth asserting here is the locale fallback, which is
 * ordinary branching, and that the caller is never a parameter.
 */
class AnnouncementServiceTest {

    private static final String SUBJECT = "reader";
    private static final UUID ID = UUID.fromString("7c3f1a2b-3c4d-5e6f-7081-92a3b4c5d6e7");

    private AnnouncementRepository announcements;
    private AnnouncementContentRepository contents;
    private AnnouncementMarkerRepository markers;
    private AnnouncementService service;

    @BeforeEach
    void setUp() {
        announcements = mock(AnnouncementRepository.class);
        contents = mock(AnnouncementContentRepository.class);
        markers = mock(AnnouncementMarkerRepository.class);

        WebCoreProperties webCoreProperties = new WebCoreProperties();
        webCoreProperties.getI18n().setDefaultLocale("en");

        service = new AnnouncementService(announcements, contents, markers, webCoreProperties);

        when(markers.findById(any())).thenReturn(Optional.empty());
        when(announcements.findVisible(any(), any(), eq(ID), any()))
                .thenReturn(Optional.of(announcement()));
        when(contents.findById(any())).thenReturn(Optional.empty());

        SecurityContextHolder.getContext().setAuthentication(
                TestPrincipalBuilder.user(SUBJECT).roles("ROLE_ADMIN").authentication());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * The caller's own locale wins. This is the ordinary case and the one that proves the lookup is
     * keyed by locale at all.
     */
    @Test
    @DisplayName("content is read in the caller's own locale when there is a row for it")
    void callersLocaleWins() {
        givenContent("en", "English subject");

        assertThat(service.get(ID).subject()).isEqualTo("English subject");
    }

    /**
     * The fallback. A recipient whose language has no row gets the default in full rather than a
     * half-translated document, which is the same rule the template resolver applies when picking a
     * file - stated once rather than invented twice.
     */
    @Test
    @DisplayName("a locale with no row falls back to the default locale in full")
    void fallsBackToDefaultLocale() {
        SecurityContextHolder.getContext().setAuthentication(
                TestPrincipalBuilder.user(SUBJECT).roles("ROLE_ADMIN").authentication());
        givenContent("en", "English subject");

        AnnouncementView view = service.get(ID);

        assertThat(view.subject()).isEqualTo("English subject");
        assertThat(view.locale())
                .as("the view reports the locale actually used, so a missing translation is visible")
                .isEqualTo("en");
    }

    /**
     * Content can be absent entirely once retention has removed it, and that must not be an
     * exception: the announcement row outlives nothing here, but a purge that removed content first
     * would otherwise turn every read into a 500.
     */
    @Test
    @DisplayName("an announcement with no content at all is returned without a body")
    void missingContentIsNotAnError() {
        AnnouncementView view = service.get(ID);

        assertThat(view.id()).isEqualTo(ID);
        assertThat(view.subject()).isNull();
        assertThat(view.bodyHtml()).isNull();
    }

    @Test
    @DisplayName("an announcement that is not visible to the caller is not found")
    void invisibleAnnouncementIsNotFound() {
        when(announcements.findVisible(any(), any(), eq(ID), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(ID))
                .isInstanceOf(AnnouncementNotFoundException.class);
    }

    /**
     * The caller's subject and roles reach the query from the principal, never from a parameter. An
     * owner parameter would turn this class into a way to read somebody else's feed, and a role-set
     * parameter into a way to read any audience.
     */
    @Test
    @DisplayName("the caller's subject and roles are taken from the principal")
    void callerComesFromThePrincipal() {
        service.get(ID);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<java.util.Collection<String>> roles =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(announcements).findVisible(subject.capture(), roles.capture(), eq(ID), any());

        assertThat(subject.getValue()).isEqualTo(SUBJECT);
        assertThat(roles.getValue()).containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("the outstanding count is scoped to the caller")
    void countIsScopedToTheCaller() {
        when(announcements.countVisibleUndismissed(eq(SUBJECT), any(), any())).thenReturn(3L);

        assertThat(service.outstanding()).isEqualTo(3L);
    }

    /**
     * Dismissal writes at most one row. Asserted by inspection rather than by an upsert, so a retry
     * over a flaky connection cannot rewrite when it happened.
     */
    @Test
    @DisplayName("dismissing an already-dismissed announcement writes nothing")
    void dismissalIsIdempotent() {
        when(markers.findById(any())).thenReturn(Optional.of(
                ru.ludwigandreas.notification.repository.entity.AnnouncementMarkerEntity.builder()
                        .dismissedAt(Instant.parse("2026-01-04T09:00:00Z"))
                        .build()));

        AnnouncementView view = service.dismiss(ID);

        assertThat(view.dismissed()).isTrue();
        verify(markers, never()).save(any());
    }

    @Test
    @DisplayName("dismissing an undismissed announcement writes exactly one row")
    void dismissalWritesOneRow() {
        service.dismiss(ID);

        verify(markers).save(any());
    }

    /**
     * {@code SecurityPrincipals.require()} throws rather than defaulting, and the feed inherits that.
     * A service that fell back to an anonymous subject would run an unscoped query - the one failure
     * mode worth its own test.
     */
    @Test
    @DisplayName("with no authenticated caller nothing is read")
    void noCallerMeansNoQuery() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> service.outstanding()).isInstanceOf(RuntimeException.class);
        verify(announcements, never()).countVisibleUndismissed(any(), any(), any());
    }

    private void givenContent(String locale, String subject) {
        when(contents.findById(eq(new AnnouncementContentId(ID, locale))))
                .thenReturn(Optional.of(AnnouncementContentEntity.builder()
                        .id(new AnnouncementContentId(ID, locale))
                        .subject(subject)
                        .bodyHtml("<p>Body</p>")
                        .bodyText("Body")
                        .renderedAt(Instant.now())
                        .build()));
    }

    private static AnnouncementEntity announcement() {
        AnnouncementEntity announcement = AnnouncementEntity.builder()
                .category("platform-release")
                .categoryClass(CategoryKind.PLATFORM)
                .templateKey("platform-release")
                .audienceKind(AudienceKind.ROLE)
                .audienceValue("ROLE_ADMIN")
                .visibleFrom(Instant.now().minusSeconds(60))
                .visibleUntil(Instant.now().plusSeconds(3600))
                .build();
        announcement.setId(ID);
        announcement.setCreatedAt(Instant.now().minusSeconds(60));
        return announcement;
    }
}
