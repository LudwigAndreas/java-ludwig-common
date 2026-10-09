package ru.ludwigandreas.notification.service.announcement;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import ru.ludwigandreas.audit.Actor;
import ru.ludwigandreas.audit.AuditCategories;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditOutcome;
import ru.ludwigandreas.audit.Resource;
import ru.ludwigandreas.notification.service.model.Audience;

/**
 * Something an administrator did to an announcement.
 *
 * <p>A typed record as the authoring surface with a {@code toAuditEvent()} that flattens into the
 * platform envelope, exactly as {@code IdempotencyAuditEvent} and {@code ExportAuditEvent} do, and
 * it goes to {@code audit-core}'s single {@code AuditSink} - there is no audit SPI here and no
 * logger named {@code *.audit}.
 *
 * <h2>Why this is audited at all</h2>
 *
 * <p>Publishing an announcement is the only operation in this service that one person can use to put
 * a message in front of the entire organisation, and possibly to email all of it. Two of this
 * change's rules are explicitly unmechanisable - whether a configured category's class is the right
 * product answer, and whether the audience somebody chose was the one that was approved - and for
 * both of them the mitigation is that the decision has a named author. That mitigation only exists
 * if this event does.
 *
 * <h2>Why the category is {@code CONFIG} and not something announcement-shaped</h2>
 *
 * <p>{@code AuditCategories} has no announcement constant, and adding one would change
 * {@code audit-core}, which has fourteen in-repo dependents - so a one-string addition would widen
 * this change's verification gate to fourteen modules. {@code CONFIG} is also a defensible fit
 * rather than merely a convenient one: publishing is an administrative act by a platform owner that
 * changes what every user sees, which is closer to configuring the platform than to a business
 * transaction a user initiated. The {@code action} carries the specificity.
 *
 * <p>If announcements grow a surface of their own, promoting a category is a separate change against
 * {@code audit-core} with its own gate.
 */
public record AnnouncementAuditEvent(Instant at, String action, UUID announcementId, String category,
                                     Audience audience, Actor actor, Map<String, Object> details) {

    /** An announcement was published. */
    public static final String PUBLISHED = "announcement-published";

    /** A published announcement's content was corrected. */
    public static final String CORRECTED = "announcement-corrected";

    /** An email broadcast was cancelled part way. */
    public static final String BROADCAST_CANCELLED = "announcement-broadcast-cancelled";

    /** The resource type this event's subject is recorded under. */
    public static final String RESOURCE_TYPE = "notification-announcement";

    public AnnouncementAuditEvent {
        at = at == null ? Instant.now() : at;
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    /** A publish, carrying the audience that was chosen and the window it was chosen for. */
    public static AnnouncementAuditEvent published(UUID id, String category, Audience audience,
                                                   Actor actor, Map<String, Object> details) {
        return new AnnouncementAuditEvent(Instant.now(), PUBLISHED, id, category, audience, actor,
                details);
    }

    /** A correction. The audience cannot change, so it is recorded as it stands. */
    public static AnnouncementAuditEvent corrected(UUID id, String category, Audience audience,
                                                   Actor actor) {
        return new AnnouncementAuditEvent(Instant.now(), CORRECTED, id, category, audience, actor,
                Map.of());
    }

    /**
     * A cancelled broadcast, carrying how many deliveries had already been created.
     *
     * <p>The count is recorded as a <b>string</b>, like every other attribute this service and
     * {@code IdempotencyAuditEvent} record. That is not cosmetic: the attributes map is persisted as
     * JSON on {@code AuditEventEntity}, which is a {@code SnapshotEntity} and immutable once
     * imported. A numeric value does not round-trip to the same Java type, so Hibernate's dirty check
     * sees the row as changed at the next flush and schedules an {@code UPDATE} - which the
     * immutability listener refuses, failing the whole transaction at commit with a message that
     * names the audit row and nothing about the cancellation that caused it.
     */
    public static AnnouncementAuditEvent broadcastCancelled(UUID id, String category,
                                                            Audience audience, Actor actor,
                                                            long created) {
        return new AnnouncementAuditEvent(Instant.now(), BROADCAST_CANCELLED, id, category, audience,
                actor, Map.of("deliveriesAlreadyCreated", String.valueOf(created)));
    }

    /**
     * This event as a platform audit event.
     *
     * <p>The audience is recorded as its kind and value - never as a resolved list of subjects. That
     * is not only because the list may be a hundred thousand long: the audit record should say what
     * the author <em>decided</em>, and they decided "everybody with ADMIN", not the particular set of
     * people who held that role at that second. A resolved list would also be personal data in a
     * record kept far longer than this service keeps anything else.
     */
    public AuditEvent toAuditEvent() {
        Map<String, Object> attributes = new LinkedHashMap<>(details);
        attributes.put("category", category);
        attributes.put("audienceKind", audience.type().name());
        if (audience.value() != null) {
            attributes.put("audienceValue", audience.value());
        }
        return AuditEvent.builder()
                .category(AuditCategories.CONFIG)
                .action(AuditCategories.CONFIG + "." + action)
                .occurredAt(at)
                .actor(actor == null ? Actor.system() : actor)
                .resource(new Resource(RESOURCE_TYPE,
                        announcementId == null ? null : announcementId.toString(), null))
                .outcome(AuditOutcome.success())
                .attributes(attributes)
                .build();
    }
}
