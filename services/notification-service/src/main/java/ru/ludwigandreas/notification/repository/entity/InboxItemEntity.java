package ru.ludwigandreas.notification.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import ru.ludwigandreas.db.core.entity.JpaBaseEntity;
import ru.ludwigandreas.odatafilter.annotation.FilterOperator;
import ru.ludwigandreas.odatafilter.annotation.FilterPolicy;
import ru.ludwigandreas.odatafilter.annotation.Filterable;

/**
 * One notification in one recipient's in-product inbox: a document its owner reads and mutates.
 *
 * <h2>Why this is not four more columns on the delivery</h2>
 *
 * <p>The argument is the one this service already makes one level up, for why retry state lives on
 * the delivery rather than on the request. These two rows are written by different parties and have
 * different lifetimes. A {@link NotificationDeliveryEntity} is a work-queue row written by this
 * service, claimed and updated by the claim query continuously, and deliberately kept narrow. An
 * inbox item is a document mutated by its recipient and kept until they have read it.
 *
 * <p>One table holding both roles would force the retention purge to give a single answer to two
 * different questions - the delivery's window runs from creation and the inbox's from being read -
 * and the delivery deliberately does not extend {@code AuditedEntity}, so a recipient's own write to
 * it would not be audited the way a user-owned mutation should be. An ArchUnit rule in this service's
 * test sources fails the build if a read-state field is ever declared on a delivery entity.
 *
 * <h2>The owner is not filterable, and that is three separate defences</h2>
 *
 * <p>{@link #ownerUserId} carries no {@link Filterable}, so no {@code $filter} can reach it however
 * it is spelled; the endpoints resolve the owner from the authenticated principal rather than from a
 * parameter; and a foreign item and a nonexistent item return the same refusal. Any one of the three
 * would be enough on a good day. Together they mean the inbox cannot be turned into an oracle for
 * whether a given person has been notified about a given thing, which - unlike a delivery row - is
 * information the subject would reasonably consider private from everybody including support.
 *
 * <p>{@link #deliveryId} is nullable, and that is the normal case rather than a gap: the delivery
 * retention window runs from creation while this one is anchored on being read, so the delivery is
 * the row that disappears first. The changelog declares the foreign key {@code ON DELETE SET NULL}
 * for exactly that reason - {@code CASCADE} would let the delivery purge take a live unread inbox
 * with it.
 */
@Entity
@Table(name = "notification_inbox_item")
// metadataName is set, unlike the delivery's policy, so the inbox's filterable surface is published
// as a document through the shared FilterMetadataController rather than discovered by probing. A
// recipient's own client is the one caller that genuinely needs to know what it may filter on, and the
// document is already filtered per caller - so publishing it exposes nothing the caller could not
// already determine by trying. The owner field is absent from it because it carries no @Filterable.
@FilterPolicy(maxDepth = 2, maxPageSize = 100, defaultPageSize = 25, maxNestedPropertyDepth = 1,
        defaultOrderBy = "createdAt desc, id asc", metadataName = "notification-inbox")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InboxItemEntity extends JpaBaseEntity<UUID> {

    /**
     * Whose inbox this is.
     *
     * <p>Deliberately carries no {@link Filterable}. Filterable means enumerable, and an endpoint
     * that answers "does any inbox item exist for this subject and this category?" tells a caller
     * what the platform has told somebody else - which is why this is stricter than the delivery's
     * {@code recipientUserId}, where the same field is exposed to notification admins. There is no
     * role for which filtering here is the right answer: support reads deliveries, not somebody's
     * inbox.
     */
    @Column(name = "owner_user_id", nullable = false, updatable = false, length = 255)
    private String ownerUserId;

    /**
     * The delivery this item was settled from, while that row still exists.
     *
     * <p>Null once the delivery has been purged, which happens first and routinely. A plain column
     * rather than a mapped association: nothing in the read path needs the delivery, and an inbox
     * list that lazily loaded one per row would be an N+1 on the service's most-called endpoint.
     */
    @Column(name = "delivery_id", updatable = false)
    private UUID deliveryId;

    /** The request this item ultimately came from, kept for support correlation. */
    @Column(name = "request_id", updatable = false)
    private UUID requestId;

    @Filterable(ops = {FilterOperator.EQ, FilterOperator.IN})
    @Column(name = "template_key", nullable = false, updatable = false, length = 255)
    private String templateKey;

    /** The business category, which is what a recipient filtering their own inbox actually wants. */
    @Filterable(ops = {FilterOperator.EQ, FilterOperator.NE, FilterOperator.IN})
    @Column(name = "category", nullable = false, updatable = false, length = 255)
    private String category;

    @Filterable(ops = {FilterOperator.EQ, FilterOperator.NE, FilterOperator.IN})
    @Enumerated(EnumType.STRING)
    @Column(name = "category_class", nullable = false, updatable = false, length = 32)
    private CategoryKind categoryClass;

    @Filterable(ops = {FilterOperator.EQ, FilterOperator.NE, FilterOperator.GE, FilterOperator.LE})
    @Column(name = "priority", nullable = false, updatable = false)
    private Short priority;

    /**
     * The language the content was rendered in, fixed at the moment it was produced.
     *
     * <p>Not filterable: it is a property of the render rather than something a recipient browses by,
     * and their own locale is not a useful filter over their own inbox.
     */
    @Column(name = "locale", nullable = false, updatable = false, length = 35)
    private String locale;

    @Filterable(ops = {FilterOperator.GE, FilterOperator.LE, FilterOperator.GT, FilterOperator.LT})
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * When the owner was shown this item in a list, as distinct from having opened it.
     *
     * <p>Set once and never reset. Monotonic because the three instants are a record of what
     * happened rather than a current state: a client that could clear them could make an unread
     * badge disagree with the list below it, and a recipient has no interest in a notification
     * becoming unseen again.
     */
    @Filterable(ops = {FilterOperator.EQ, FilterOperator.NE})
    @Column(name = "seen_at")
    private Instant seenAt;

    /**
     * When the owner read it. Setting this also sets {@link #seenAt} if it was unset, because
     * reading something one was never shown is not a state worth being able to represent.
     *
     * <p>This is the retention anchor. The purge is over {@code COALESCE(read_at, dismissed_at)}, so
     * an item nobody has read is not purged by age alone - which is the one thing the inbox must get
     * right that the other windows do not have to.
     */
    @Filterable(ops = {FilterOperator.EQ, FilterOperator.NE})
    @Column(name = "read_at")
    private Instant readAt;

    /** When the owner dismissed it: excluded from the default list, still fetchable by id. */
    @Filterable(ops = {FilterOperator.EQ, FilterOperator.NE})
    @Column(name = "dismissed_at")
    private Instant dismissedAt;
}
