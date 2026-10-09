package ru.ludwigandreas.notification.service.announcement;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.audit.Actor;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.notification.repository.AnnouncementContentRepository;
import ru.ludwigandreas.notification.repository.AnnouncementRepository;
import ru.ludwigandreas.notification.repository.entity.AnnouncementContentEntity;
import ru.ludwigandreas.notification.repository.entity.AnnouncementContentId;
import ru.ludwigandreas.notification.repository.entity.AnnouncementEntity;
import ru.ludwigandreas.notification.repository.entity.AudienceKind;
import ru.ludwigandreas.notification.repository.entity.CategoryKind;
import ru.ludwigandreas.notification.service.exception.AnnouncementNotFoundException;
import ru.ludwigandreas.notification.service.exception.UnknownAnnouncementCategoryException;
import ru.ludwigandreas.notification.service.exception.VisibilityWindowTooLongException;
import ru.ludwigandreas.notification.service.model.Audience;
import ru.ludwigandreas.notification.service.model.AnnouncementAdminView;
import ru.ludwigandreas.notification.service.model.AudienceType;
import ru.ludwigandreas.notification.service.model.CategoryClass;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.template.RenderedTemplate;
import ru.ludwigandreas.notification.service.template.TemplateCoordinates;
import ru.ludwigandreas.notification.service.template.TemplateRenderer;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.security.authz.Authorities;
import ru.ludwigandreas.security.principal.SecurityPrincipals;
import ru.ludwigandreas.webcore.config.WebCoreProperties;

/**
 * Publishing and correcting announcements: everything an administrator does, and nothing a recipient
 * does.
 *
 * <h2>What the request decides and what the catalogue decides</h2>
 *
 * <p>A publish carries only data: the audience, the template, the variables and the window. It
 * carries no channel set and no flag asking for email, and the reason is the argument
 * {@code PreferenceEvaluator} already makes about the transactional bypass - anything an announcer
 * can set, every announcer will set to the most permissive value, because from inside any one team
 * its own announcement always looks important.
 *
 * <p>So the <b>category</b> decides the class and the channels, out of a catalogue owned by whoever
 * owns the notification catalogue. A category this deployment has not configured is rejected rather
 * than defaulted: a default would silently pick both a declinability and whether a hundred thousand
 * emails are sent.
 *
 * <h2>Every locale is rendered before anything is stored</h2>
 *
 * <p>An announcement is one row, but recipients differ in language, so the template is rendered once
 * per configured supported locale and stored one row per locale. The row count therefore follows the
 * number of languages the deployment ships bundles for - two today - and never the size of the
 * audience, which is what keeps the aggregate's one-row property intact.
 *
 * <p>The renders all happen <b>before</b> the first insert. A template that compiles in English and
 * fails in Russian must reject the whole publish rather than produce an announcement half the estate
 * cannot read, and the only way to guarantee that is to have every render in hand before committing
 * to any of them.
 *
 * <p>Rendering at read time was considered and is not possible: {@code recipient-data-ttl} scrubs the
 * variable map, so an announcement still unread in its third week could no longer be produced at all
 * - the same reason the inbox renders at fan-out rather than lazily.
 *
 * <h2>Content can be corrected; the audience cannot</h2>
 *
 * <p>Correcting re-renders every locale together, so the languages cannot drift into saying different
 * things, and leaves dismissal markers untouched - somebody who dismissed an announcement does not
 * un-dismiss it because a typo was fixed. The audience is immutable after publication because part of
 * it may already have been emailed, and changing it would make the record of who was told untrue.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementAdminService {

    private final AnnouncementRepository announcements;
    private final AnnouncementContentRepository contents;
    private final AnnouncementAudienceResolver audienceResolver;
    private final TemplateRenderer templateRenderer;
    private final NotificationProperties properties;
    private final WebCoreProperties webCoreProperties;
    private final AuditSink auditSink;
    private final AnnouncementEmailBroadcastService broadcastService;

    /**
     * Publishes one announcement.
     *
     * <p>One transaction: the announcement and every locale's content commit together or not at all.
     * A half-published announcement is one that exists and cannot be read, which is worse than one
     * that was rejected.
     *
     * @return the stored announcement, already visible if its window has opened
     */
    @Transactional
    public PublishedAnnouncement publish(String category, AudienceType audienceType,
                                      String audienceValue, String templateKey,
                                      Map<String, Object> variables, Instant visibleFrom,
                                      Instant visibleUntil) {
        NotificationProperties.Announcements.AnnouncementCategory configured = requireCategory(category);
        // Resolved against the deployment's allowlist on the BARE code - which is what an
        // administrator writes in the configuration - then normalised once, here, into the
        // ROLE_-prefixed form everything downstream of this line uses: the stored column, the
        // visibility predicate it is compared against, the audit record and the admin response.
        // Keeping both forms alive past this point is how the two drift.
        Audience audience = normalized(audienceResolver.resolve(audienceType, audienceValue));
        Instant from = visibleFrom == null ? Instant.now() : visibleFrom;
        requireWindowWithinLimit(from, visibleUntil);

        // Rendered first, all of them, and only then stored. See the class javadoc: a locale that
        // fails must reject the publish rather than leave part of the estate unable to read it.
        Map<String, RenderedTemplate> rendered = renderEveryLocale(templateKey, variables);

        AnnouncementEntity announcement = AnnouncementEntity.builder()
                .category(category)
                // Snapshotted from the catalogue rather than read on every use: a category can be
                // reconfigured, and an announcement must keep the declinability it was published
                // under, or a reclassification would retroactively change whether people had been
                // allowed to decline announcements they already received.
                .categoryClass(CategoryKind.valueOf(configured.getCategoryClass()))
                .templateKey(templateKey)
                .audienceKind(AudienceKind.valueOf(audience.type().name()))
                // Already normalised - see the resolve call above.
                .audienceValue(audience.value())
                .visibleFrom(from)
                .visibleUntil(visibleUntil)
                .build();
        // The id is DATABASE-generated: AnnouncementEntity extends AuditedEntity, which extends
        // GeneratedEntity. So it must be read back after the save rather than assigned beforehand -
        // assigning one and using it for the children produces content rows pointing at an id that
        // was never inserted. (The inbox does assign its own, because InboxItemEntity extends
        // JpaBaseEntity, where the id is application-assigned. The two base classes differ and the
        // difference is load-bearing.)
        //
        // Flushed as well as saved, so the children's foreign key references a row that exists
        // rather than relying on Hibernate's insert ordering - the same reason
        // DeliveryFanOutService flushes the delivery before recording its first status transition.
        announcement = announcements.saveAndFlush(announcement);
        UUID id = announcement.getId();

        storeContent(id, rendered, Instant.now());

        UUID emailRunId = null;

        // Audited because this is the only operation in the service that one person can use to put a
        // message in front of the whole organisation. Two of this change's rules are explicitly
        // unmechanisable - whether the category's class is right, and whether this was the audience
        // somebody approved - and for both the mitigation is that the decision has a named author.
        // No try/catch: whether a sink failure fails the caller is AuditFailurePolicy's decision,
        // resolved from configuration, and a catch here would override it.
        auditSink.record(AnnouncementAuditEvent.published(id, category, audience, currentActor(),
                Map.of("visibleFrom", from.toString(),
                        "visibleUntil", visibleUntil.toString(),
                        "channels", String.join(",", configured.getChannels()),
                        "locales", String.join(",", rendered.keySet()))).toAuditEvent());

        // A run only where the category's channels include EMAIL, so an inbox-only announcement
        // names no operation to poll. Created in THIS transaction, so the run and the announcement
        // commit together: a run whose announcement rolled back would walk an audience for something
        // nobody can see, and an announcement whose run was lost would silently never email anybody.
        if (configured.getChannels().contains(ChannelType.EMAIL.name())) {
            emailRunId = broadcastService.startRun(id).id();
            log.info("Announcement {} will also be emailed; fan-out run {}", id, emailRunId);
        }

        log.info("Published announcement {} in category {} to {}{} until {}", id, category,
                audience.type(), audience.value() == null ? "" : "(" + audience.value() + ")",
                visibleUntil);
        return new PublishedAnnouncement(toAdminView(announcement), emailRunId);
    }

    /**
     * What a publish produced: the announcement, and the fan-out run if its category emails.
     *
     * <p>A pair rather than two calls, because the two are created in one transaction and a caller
     * that had to ask for the run afterwards could observe an announcement whose run had not been
     * committed yet.
     *
     * @param emailRunId null for an inbox-only category, which is the difference between a response
     *                   that names a status resource and one that does not
     */
    public record PublishedAnnouncement(AnnouncementAdminView announcement, UUID emailRunId) {
    }

    /**
     * The entity as the layer above sees it.
     *
     * <p>Hand-written rather than a MapStruct mapping because the audience is two columns becoming
     * two differently-typed fields and the enums are twins - which is three {@code @Mapping}
     * attributes to say what six lines say plainly. The entity stops here: a controller handling one
     * fails the layering rules, and rightly, because it ties the published API to a column.
     */
    private static AnnouncementAdminView toAdminView(AnnouncementEntity announcement) {
        return new AnnouncementAdminView(
                announcement.getId(),
                announcement.getCategory(),
                CategoryClass.valueOf(announcement.getCategoryClass().name()),
                AudienceType.valueOf(announcement.getAudienceKind().name()),
                announcement.getAudienceValue(),
                announcement.getTemplateKey(),
                announcement.getVisibleFrom(),
                announcement.getVisibleUntil(),
                announcement.getCreatedAt(),
                announcement.getCreatedBy());
    }

    /**
     * Re-renders a published announcement's content, in every locale.
     *
     * <p>Every locale together rather than one at a time, so the languages cannot drift apart: a
     * correction applied to English alone would leave the Russian text saying the thing that was
     * wrong, which is worse than the original typo because nobody would know.
     */
    @Transactional
    public AnnouncementAdminView correct(UUID announcementId, Map<String, Object> variables) {
        AnnouncementEntity announcement = announcements.findForAdministration(announcementId)
                .orElseThrow(() -> new AnnouncementNotFoundException(announcementId));

        Map<String, RenderedTemplate> rendered =
                renderEveryLocale(announcement.getTemplateKey(), variables);
        storeContent(announcementId, rendered, Instant.now());

        auditSink.record(AnnouncementAuditEvent.corrected(announcementId,
                announcement.getCategory(), audienceOf(announcement), currentActor()).toAuditEvent());

        log.info("Corrected announcement {} in {} locale(s)", announcementId, rendered.size());
        return toAdminView(announcement);
    }

    /** The channels this announcement's category is delivered over. */
    public List<ChannelType> channelsFor(String category) {
        List<ChannelType> channels = new ArrayList<>();
        for (String name : requireCategory(category).getChannels()) {
            channels.add(ChannelType.valueOf(name));
        }
        return channels;
    }

    /**
     * Renders the template once per supported locale.
     *
     * <p>No {@code try}/{@code catch}: a render failure must propagate and abort the publish. The
     * dispatcher treats a render failure as terminal for one delivery because the other recipients
     * are unaffected; here there is only one thing being created, and producing it in some languages
     * and not others is not a partial success.
     */
    private Map<String, RenderedTemplate> renderEveryLocale(String templateKey,
                                                            Map<String, Object> variables) {
        Map<String, RenderedTemplate> rendered = new LinkedHashMap<>();
        for (String tag : webCoreProperties.getI18n().getSupportedLocales()) {
            RenderedTemplate template = templateRenderer.render(
                    new TemplateCoordinates(templateKey, ChannelType.IN_APP,
                            Locale.forLanguageTag(tag)),
                    variables == null ? Map.of() : variables);
            // Keyed by the requested tag rather than by the resolved one. The resolver falls back to
            // the default language when a translation is missing, so two supported locales can
            // resolve to the same file - and keying by the resolved locale would silently store one
            // row instead of two, leaving a reader's own locale with no row at all.
            rendered.put(tag, template);
        }
        return rendered;
    }

    private void storeContent(UUID announcementId, Map<String, RenderedTemplate> rendered,
                              Instant at) {
        rendered.forEach((tag, template) -> contents.save(AnnouncementContentEntity.builder()
                .id(new AnnouncementContentId(announcementId, tag))
                .subject(template.subject())
                .bodyHtml(template.htmlBody())
                .bodyText(template.textBody())
                .renderedAt(at)
                .build()));
    }

    private NotificationProperties.Announcements.AnnouncementCategory requireCategory(String category) {
        NotificationProperties.Announcements.AnnouncementCategory configured =
                properties.getAnnouncements().getCategories().get(category);
        if (configured == null) {
            throw new UnknownAnnouncementCategoryException(category);
        }
        return configured;
    }

    private void requireWindowWithinLimit(Instant from, Instant until) {
        if (until == null || !until.isAfter(from)) {
            throw new VisibilityWindowTooLongException(Duration.ZERO,
                    properties.getAnnouncements().getMaxVisibilityWindow());
        }
        Duration requested = Duration.between(from, until);
        Duration permitted = properties.getAnnouncements().getMaxVisibilityWindow();
        if (requested.compareTo(permitted) > 0) {
            throw new VisibilityWindowTooLongException(requested, permitted);
        }
    }

    /**
     * The audience in the form everything downstream of persistence uses.
     *
     * <p>A principal's roles are normalised to the {@code ROLE_} prefix at the edge of the service,
     * and the visibility predicate compares the stored {@code audience_value} against them directly.
     * So the stored form must be prefixed too, and the normalisation happens exactly once - here.
     *
     * <p>Getting this wrong is silent, which is why it is a named method rather than an inline call:
     * a bare {@code ADMIN} stored against a prefixed {@code ROLE_ADMIN} principal matches nothing, so
     * the announcement publishes cleanly, reports success, and is visible to nobody at all.
     */
    private static Audience normalized(Audience audience) {
        if (audience.value() == null) {
            return audience;
        }
        return Audience.ofRole(Authorities.normalizeRole(audience.value()));
    }

    private static Audience audienceOf(AnnouncementEntity announcement) {
        return new Audience(AudienceType.valueOf(announcement.getAudienceKind().name()),
                announcement.getAudienceValue());
    }

    /** The publishing administrator, for the audit record. */
    private static Actor currentActor() {
        return SecurityPrincipals.current()
                .map(principal -> Actor.of(principal.subject()))
                .orElseGet(Actor::system);
    }

    /** Exposed for the broadcast, which needs the class an announcement was published under. */
    public static CategoryClass categoryClassOf(AnnouncementEntity announcement) {
        return CategoryClass.valueOf(announcement.getCategoryClass().name());
    }
}
