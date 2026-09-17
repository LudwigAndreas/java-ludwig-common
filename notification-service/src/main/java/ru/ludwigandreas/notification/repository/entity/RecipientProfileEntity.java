package ru.ludwigandreas.notification.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.ludwigandreas.db.core.entity.AuditedEntity;

/**
 * How to reach one user, in what language, and when not to.
 *
 * <p>This table exists because the identity projection cannot supply it and should not.
 * {@code identity-projection-spring-boot-starter} stores a user's subject, display name, tenant,
 * status and roles, and its own documentation gives the reason: it is directory data replicated into
 * every service that uses the module, so every extra field is another place a personal-data request
 * has to reach. An email address, a chat handle and a home timezone are not authorization inputs and
 * have no business being copied estate-wide.
 *
 * <p>So the split is: the identity projection answers "does this user exist, are they active, what
 * do we call them, whose tenant are they in"; this table answers "where do we send it and when".
 * The notification service needs the second half anyway - preferences and quiet hours are its own
 * domain - so it owns the contact record rather than asking a module to grow one.
 *
 * <p>Keyed by a generated id with the subject as a unique business key, rather than keyed by the
 * subject itself, so a profile can exist for a recipient who has no account at all (an external
 * address a partner asked us to write to).
 */
@Entity
@Table(name = "notification_recipient_profile")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecipientProfileEntity extends AuditedEntity<UUID> {

    /** The OIDC subject this profile describes. Unique. */
    @Column(name = "user_id", nullable = false, updatable = false, length = 255)
    private String userId;

    @Column(name = "email_address", length = 320)
    private String emailAddress;

    @Column(name = "chat_address", length = 255)
    private String chatAddress;

    @Column(name = "webhook_url", length = 1024)
    private String webhookUrl;

    /** BCP 47 tag. Falls back to the configured default when absent, never to the server's locale. */
    @Column(name = "locale", length = 35)
    private String locale;

    /** IANA zone id. Quiet hours are evaluated here, which is the only place they mean anything. */
    @Column(name = "timezone", length = 64)
    private String timezone;

    /**
     * Start of the recipient's quiet period, in {@link #timezone}. Null disables quiet hours.
     *
     * <p>A window that wraps midnight (22:00 to 07:00) is normal and is handled by comparing against
     * both ends rather than by assuming start &lt; end.
     */
    @Column(name = "quiet_hours_start")
    private LocalTime quietHoursStart;

    @Column(name = "quiet_hours_end")
    private LocalTime quietHoursEnd;

    @Column(name = "tenant_id", length = 128)
    private String tenantId;
}
