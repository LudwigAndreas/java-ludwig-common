package ru.ludwigandreas.notification.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.ludwigandreas.db.core.entity.AuditedEntity;

/**
 * One opt-out: this recipient does not want this category on this channel.
 *
 * <p>Stored as explicit rows rather than as a bitmask or a JSON blob on the profile, because the
 * question asked at fan-out is "is there a row denying this exact combination?", which is an indexed
 * lookup, and because an opt-out is an auditable act - {@code createdBy}/{@code createdAt} from
 * {@link AuditedEntity} record who set it and when, which is what a complaint is answered with.
 *
 * <p>Absence means allowed. Opting out is the exception, so the table stays small and the default is
 * the one that does not silently swallow notifications.
 */
@Entity
@Table(name = "notification_recipient_preference")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecipientPreferenceEntity extends AuditedEntity<UUID> {

    @Column(name = "user_id", nullable = false, updatable = false, length = 255)
    private String userId;

    /** The category declined, or {@code *} for every marketing category at once. */
    @Column(name = "category", nullable = false, updatable = false, length = 128)
    private String category;

    /** The channel declined. Null means every channel. */
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", updatable = false, length = 16)
    private ChannelKind channel;

    /**
     * {@code false} is an opt-out; {@code true} is an explicit opt-in that overrides a wildcard
     * opt-out for one category.
     *
     * <p>Without the explicit-allow case, a recipient who declined everything could never re-enable
     * one thing without the wildcard being deleted, which loses the record that they had declined.
     */
    @Column(name = "allowed", nullable = false)
    private boolean allowed;

    /** How the preference was set: {@code self-service}, {@code support}, {@code import}. */
    @Column(name = "source", nullable = false, length = 64)
    private String source;
}
