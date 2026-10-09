package ru.ludwigandreas.notification.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.ludwigandreas.db.core.entity.JpaBaseEntity;

/**
 * The rendered body of one inbox item.
 *
 * <p>A separate table for the same two reasons {@link DeliveryContentEntity} is one: the item row is
 * what the list, the default filter and the unread count all scan, and it wants to stay narrow rather
 * than carry a multi-kilobyte body; and content in its own table gives the retention purge a single
 * cheap target. The primary key <em>is</em> the item id, which makes the one-to-one structural.
 *
 * <h2>Why this is not a row in {@link DeliveryContentEntity}</h2>
 *
 * <p>The tables look identical and their retention windows are not, which is the whole reason there
 * are two. {@code notification_delivery_content} is purged at {@code retention.content-ttl} - seven
 * days by default - with a startup check that a body never outlives the delivery that owns it. An
 * unread inbox item has to survive somebody going on holiday, so reusing that table would delete the
 * notification out from under its owner on day eight.
 *
 * <p>It is also a different hazard class. A delivery body is kept so that an operator can answer
 * "what did we actually send?", which is why it is treated as the most sensitive thing this service
 * holds. An inbox body is the thing the recipient is <em>meant</em> to read. Both are protected; they
 * are not protected from the same party, and conflating them would mean choosing one window for two
 * purposes. An ArchUnit rule fails the build if the in-app settlement path reaches the delivery
 * content table.
 *
 * <h2>Rendered at fan-out, unlike every other channel</h2>
 *
 * <p>This service deliberately does not render at fan-out - see {@code DeliveryFanOutService} - on
 * the grounds that rendering is CPU work only needed at send time, and that a template about to be
 * corrected would otherwise bake the old wording into every queued delivery. A passive channel has no
 * later send step for that argument to apply to, and deferring the render to read time is not merely
 * awkward but impossible: {@code retention.recipient-data-ttl} scrubs the variable map after seven
 * days, so the content of an item still unread in week three could no longer be produced at all.
 *
 * <p>The consequence is accepted rather than hidden: an in-app notification carries the wording that
 * was current when it was produced, and correcting a template does not retrospectively correct an
 * item already in somebody's inbox. That is the same promise as for an email already sent.
 */
@Entity
@Table(name = "notification_inbox_item_content")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InboxItemContentEntity extends JpaBaseEntity<UUID> {

    /** Rendered subject line. */
    @Column(name = "subject", length = 998)
    private String subject;

    /** Rendered HTML part. */
    @Column(name = "body_html", columnDefinition = "text")
    private String bodyHtml;

    /** Rendered plain-text part, for a client that renders its own markup. */
    @Column(name = "body_text", columnDefinition = "text")
    private String bodyText;

    /**
     * When the content was produced.
     *
     * <p>Not the retention anchor: the purge is driven by when the <em>item</em> was read, which is
     * on the item and is the whole point of the split.
     */
    @Column(name = "rendered_at", nullable = false)
    private Instant renderedAt;
}
