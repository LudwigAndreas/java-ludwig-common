package ru.ludwigandreas.notification.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import ru.ludwigandreas.db.core.entity.AuditedEntity;
import ru.ludwigandreas.odatafilter.annotation.FilterOperator;
import ru.ludwigandreas.odatafilter.annotation.FilterPolicy;
import ru.ludwigandreas.odatafilter.annotation.Filterable;

/**
 * One platform announcement: one message for an audience defined by a rule.
 *
 * <h2>The audience is a predicate, and that is the aggregate</h2>
 *
 * <p>{@link #audienceKind} and {@link #audienceValue} describe <em>who</em> without enumerating
 * them. Publishing to a hundred thousand people writes this one row; publishing to ten writes this
 * one row. A user created tomorrow is inside an {@code EVERYONE} audience with nothing rewritten, a
 * typo is corrected with one update rather than a hundred thousand, and retention is a deletion
 * rather than a sweep.
 *
 * <p>The alternative - one row per recipient - is what a caller enumerating users and submitting
 * batches produces today, and it costs 50 000 delivery rows, 50 000 inbox items and 50 000 copies of
 * the same body for one release note. An ArchUnit rule fails the build if a collection of subjects
 * or a per-subject audience association is ever added here as an optimisation, because that
 * reintroduces every one of those problems at once.
 *
 * <h2>Why there is no stored audience membership, even as a cache</h2>
 *
 * <p>Visibility is resolved on every read from the caller's own principal, so a revoked role stops
 * granting it immediately. A materialized membership would be stale exactly when it matters - after a
 * demotion - and would need a cleanup job nobody would notice had stopped running. This is the same
 * argument {@code security-spring-boot-starter} uses for resolving authorities from the projection
 * rather than trusting a token claim.
 *
 * <h2>The audience is not filterable</h2>
 *
 * <p>Neither audience column carries {@link Filterable}. Filterable means enumerable, and a query
 * answering "which announcements target {@code ROLE_X}" tells a caller which roles the platform
 * addresses - and, over time, which roles exist. The recipient feed does not need it: a recipient
 * filters their own announcements by category and date, never by an audience they are already inside.
 *
 * <p>Extends {@link AuditedEntity} because an announcement is caller-owned - somebody published it,
 * and "who published this, and when" is the first question asked about one. That is the same reason
 * {@link NotificationRequestEntity} is audited and {@link NotificationDeliveryEntity} deliberately
 * is not.
 */
@Entity
@Table(name = "notification_announcement")
@EntityListeners(AuditingEntityListener.class)
@FilterPolicy(maxDepth = 2, maxPageSize = 100, defaultPageSize = 25, maxNestedPropertyDepth = 1,
        defaultOrderBy = "createdAt desc, id asc", metadataName = "notification-announcement")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnnouncementEntity extends AuditedEntity<UUID> {

    /** The catalogue category this was published under; the catalogue decides its class and channels. */
    @Filterable(ops = {FilterOperator.EQ, FilterOperator.NE, FilterOperator.IN})
    @Column(name = "category", nullable = false, updatable = false, length = 255)
    private String category;

    /**
     * Snapshotted from the catalogue at publish rather than looked up on read.
     *
     * <p>A category's class can be reconfigured, and an announcement must keep the declinability it
     * was published under - otherwise a deployment that reclassified a category would retroactively
     * change whether people had been allowed to decline announcements they already received.
     */
    @Filterable(ops = {FilterOperator.EQ, FilterOperator.NE, FilterOperator.IN})
    @Enumerated(EnumType.STRING)
    @Column(name = "category_class", nullable = false, updatable = false, length = 32)
    private CategoryKind categoryClass;

    @Filterable(ops = {FilterOperator.EQ, FilterOperator.IN})
    @Column(name = "template_key", nullable = false, updatable = false, length = 255)
    private String templateKey;

    /**
     * Who this is for, as a kind. Deliberately not filterable - see the class javadoc.
     *
     * <p>Not updatable: part of the audience may already have been emailed, so changing it would make
     * the record of who was told untrue.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "audience_kind", nullable = false, updatable = false, length = 32)
    private AudienceKind audienceKind;

    /**
     * The role code for a {@code ROLE} audience, null for {@code EVERYONE}.
     *
     * <p>A database check constraint enforces that pairing, because a null role code would make the
     * visibility predicate match nobody - silently, for the lifetime of the announcement.
     */
    @Column(name = "audience_value", updatable = false, length = 128)
    private String audienceValue;

    @Filterable(ops = {FilterOperator.GE, FilterOperator.LE, FilterOperator.GT, FilterOperator.LT})
    @Column(name = "visible_from", nullable = false)
    private Instant visibleFrom;

    /**
     * When it stops being visible. Mandatory, and the anchor retention is measured from.
     *
     * <p>An announcement with no end is a banner nobody removes, and - because retention runs from
     * here - a row that is never purged. The permitted length is capped by configuration.
     */
    @Filterable(ops = {FilterOperator.GE, FilterOperator.LE, FilterOperator.GT, FilterOperator.LT})
    @Column(name = "visible_until", nullable = false)
    private Instant visibleUntil;
}
