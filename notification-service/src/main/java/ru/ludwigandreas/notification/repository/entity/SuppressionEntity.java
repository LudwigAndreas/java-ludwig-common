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
import ru.ludwigandreas.db.core.entity.AuditedEntity;

/**
 * An address this service will not write to again, whatever any preference says.
 *
 * <p>A suppression is a fact about the destination, not a wish of its owner, which is why it sits
 * apart from {@link RecipientPreferenceEntity} and why even a
 * {@link CategoryKind#TRANSACTIONAL} notification honours it. Continuing to send to an address that
 * hard-bounced or that filed a spam complaint damages the sending domain's reputation for every
 * other recipient, so this list is the one rule with no bypass.
 *
 * <p>Fed by the receipt webhook (bounce, complaint) and by operators. Checked twice: at fan-out, so
 * a suppressed delivery never enters the queue, and again immediately before dispatch, because a
 * recipient can unsubscribe in the minutes a delivery spends waiting.
 */
@Entity
@Table(name = "notification_suppression")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SuppressionEntity extends AuditedEntity<UUID> {

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false, length = 16)
    private ChannelKind channel;

    /**
     * The suppressed destination, normalized (lower-cased and trimmed) by the service before it is
     * written, so a lookup is an equality match on a unique index rather than a function scan.
     */
    @Column(name = "address", nullable = false, updatable = false, length = 512)
    private String address;

    /** {@code hard-bounce}, {@code complaint}, {@code unsubscribe}, {@code manual}. */
    @Column(name = "reason", nullable = false, length = 64)
    private String reason;

    /** Free-text context from the provider callback that created it. Never the message body. */
    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    /**
     * When the suppression lapses, or null for permanent.
     *
     * <p>A soft bounce ("mailbox full") should expire; a complaint should not. Encoding that here
     * rather than in the purge job means the distinction survives a change of retention policy.
     */
    @Column(name = "expires_at")
    private Instant expiresAt;
}
