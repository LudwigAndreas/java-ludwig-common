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
 * The rendered body of one delivery, kept so an operator can answer "what did we actually send?".
 *
 * <p>A separate table rather than three more columns on {@link NotificationDeliveryEntity}, for two
 * reasons that both matter. The delivery row is scanned and updated by the claim query thousands of
 * times a minute and wants to stay narrow - a multi-kilobyte HTML body on it would be TOAST-ed, but
 * the row would still be wider and the table's pages fewer per read. And rendered content is the
 * most sensitive thing this service holds, so giving it its own table gives the retention purge a
 * single cheap target: bodies can be dropped on a much shorter schedule than the delivery metadata
 * that operations needs to keep.
 *
 * <p>The primary key <em>is</em> the delivery id ({@link JpaBaseEntity}, application-assigned), which
 * makes the one-to-one structural rather than conventional.
 */
@Entity
@Table(name = "notification_delivery_content")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryContentEntity extends JpaBaseEntity<UUID> {

    /** Rendered subject line. Null on channels that have no notion of one. */
    @Column(name = "subject", length = 998)
    private String subject;

    /** Rendered HTML part, or the JSON body for the chat and webhook channels. */
    @Column(name = "body_html", columnDefinition = "text")
    private String bodyHtml;

    /** Rendered plain-text part. Always produced for email, so a text-only client is never empty. */
    @Column(name = "body_text", columnDefinition = "text")
    private String bodyText;

    @Column(name = "rendered_at", nullable = false, updatable = false)
    private Instant renderedAt;

    /** When the purge may drop this row, independently of the delivery's own retention. */
    @Column(name = "purge_after", nullable = false)
    private Instant purgeAfter;
}
