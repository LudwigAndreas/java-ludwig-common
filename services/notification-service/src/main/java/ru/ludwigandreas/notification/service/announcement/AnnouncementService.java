package ru.ludwigandreas.notification.service.announcement;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.notification.repository.AnnouncementContentRepository;
import ru.ludwigandreas.notification.repository.AnnouncementMarkerRepository;
import ru.ludwigandreas.notification.repository.AnnouncementRepository;
import ru.ludwigandreas.notification.repository.entity.AnnouncementContentEntity;
import ru.ludwigandreas.notification.repository.entity.AnnouncementContentId;
import ru.ludwigandreas.notification.repository.entity.AnnouncementEntity;
import ru.ludwigandreas.notification.repository.entity.AnnouncementMarkerEntity;
import ru.ludwigandreas.notification.repository.entity.AnnouncementMarkerId;
import ru.ludwigandreas.notification.service.exception.AnnouncementNotFoundException;
import ru.ludwigandreas.notification.service.model.AnnouncementView;
import ru.ludwigandreas.notification.service.model.CategoryClass;
import ru.ludwigandreas.odatafilter.core.ODataQueryOptions;
import ru.ludwigandreas.odatafilter.execution.ODataPage;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.SecurityPrincipals;
import ru.ludwigandreas.webcore.config.WebCoreProperties;
import ru.ludwigandreas.webcore.preference.UserPreferences;

/**
 * Everything a recipient can do with the announcements addressed to them.
 *
 * <h2>Visibility is resolved on every read, from the caller's own principal</h2>
 *
 * <p>No announcement stores who can see it. Each read asks "is this caller inside this audience,
 * now", from the roles already carried on their principal - so a revoked role stops granting
 * visibility on the very next request, with no job, no sweep and nothing to go stale. That is the
 * same argument {@code security-spring-boot-starter} uses for resolving authorities from the local
 * projection rather than trusting a token claim: revocation latency is what settles it.
 *
 * <p>The cost is that correctness here is <em>derived</em> rather than being the obvious
 * {@code owner = me} the inbox relies on, which is precisely why announcements are their own feed and
 * their own service. An ArchUnit rule forbids the inbox read path from depending on anything here, so
 * the endpoint every client polls keeps its boring, obviously-correct predicate.
 *
 * <h2>No method takes an owner, a role set, or an instant</h2>
 *
 * <p>All three come from the caller and the clock. An owner parameter would turn this class into a
 * way to read somebody else's feed; a role-set parameter would turn it into a way to read any
 * audience; and a caller-supplied "now" would let somebody read an announcement before it was
 * published or after it expired. Each would look entirely reasonable in a diff, which is why none of
 * them exists.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementService {

    private final AnnouncementRepository announcements;
    private final AnnouncementContentRepository contents;
    private final AnnouncementMarkerRepository markers;
    private final WebCoreProperties webCoreProperties;

    /** One page of the announcements visible to the caller, excluding what they have dismissed. */
    @Transactional(readOnly = true)
    public ODataPage<AnnouncementView> list(ODataQueryOptions options) {
        LudwigPrincipal caller = SecurityPrincipals.require();
        return announcements
                .searchVisible(caller.subject(), caller.roles(), Instant.now(), options)
                .map(announcement -> toView(announcement, false));
    }

    /** How many visible announcements the caller has not dismissed. */
    @Transactional(readOnly = true)
    public long outstanding() {
        LudwigPrincipal caller = SecurityPrincipals.require();
        return announcements.countVisibleUndismissed(caller.subject(), caller.roles(), Instant.now());
    }

    /**
     * One announcement the caller may see, in their language.
     *
     * <p>Includes announcements they have dismissed: dismissing is not deleting, and a client may
     * hold a link to one it has just dismissed.
     */
    @Transactional(readOnly = true)
    public AnnouncementView get(UUID announcementId) {
        LudwigPrincipal caller = SecurityPrincipals.require();
        AnnouncementEntity announcement = announcements
                .findVisible(caller.subject(), caller.roles(), announcementId, Instant.now())
                .orElseThrow(() -> new AnnouncementNotFoundException(announcementId));
        return toView(announcement, isDismissedBy(announcementId, caller.subject()));
    }

    /**
     * Dismisses an announcement for this caller alone.
     *
     * <p>Writes one marker row, and only on the first dismissal. Idempotent by inspection rather than
     * by an upsert: a repeat leaves the original instant alone, which matters because a client over a
     * flaky connection will retry and must not rewrite when it happened.
     */
    @Transactional
    public AnnouncementView dismiss(UUID announcementId) {
        LudwigPrincipal caller = SecurityPrincipals.require();
        AnnouncementEntity announcement = announcements
                .findVisible(caller.subject(), caller.roles(), announcementId, Instant.now())
                .orElseThrow(() -> new AnnouncementNotFoundException(announcementId));

        AnnouncementMarkerId id = new AnnouncementMarkerId(announcementId, caller.subject());
        if (markers.findById(id).isEmpty()) {
            markers.save(AnnouncementMarkerEntity.builder()
                    .id(id)
                    .dismissedAt(Instant.now())
                    .build());
        }
        return toView(announcement, true);
    }

    /**
     * The announcement in the caller's own language, falling back to the configured default.
     *
     * <p>The same fallback rule the template resolver already applies when picking a template file,
     * rather than a second rule a reader would have to discover. A recipient whose language has no
     * row gets the default in full - never a half-translated document, which is also why the publish
     * renders every locale before storing any of them.
     */
    private AnnouncementView toView(AnnouncementEntity announcement, boolean dismissed) {
        String locale = UserPreferences.current().locale().toLanguageTag();
        AnnouncementContentEntity content = contentIn(announcement.getId(), locale)
                .or(() -> contentIn(announcement.getId(),
                        Locale.forLanguageTag(locale).getLanguage()))
                .or(() -> contentIn(announcement.getId(),
                        webCoreProperties.getI18n().getDefaultLocale()))
                .orElse(null);

        return new AnnouncementView(
                announcement.getId(),
                announcement.getCategory(),
                CategoryClass.valueOf(announcement.getCategoryClass().name()),
                content == null ? null : content.getId().getLocale(),
                content == null ? null : content.getSubject(),
                content == null ? null : content.getBodyHtml(),
                content == null ? null : content.getBodyText(),
                announcement.getVisibleFrom(),
                announcement.getVisibleUntil(),
                announcement.getCreatedAt(),
                dismissed);
    }

    private Optional<AnnouncementContentEntity> contentIn(UUID announcementId, String locale) {
        return contents.findById(new AnnouncementContentId(announcementId, locale));
    }

    private boolean isDismissedBy(UUID announcementId, String subject) {
        return markers.findById(new AnnouncementMarkerId(announcementId, subject)).isPresent();
    }
}
