package ru.ludwigandreas.notification.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The composite key of a dismissal: the announcement and the person who dismissed it.
 *
 * <p>The pair is the identity - somebody either dismissed an announcement or did not - so a surrogate
 * id would permit two rows saying the same thing and make the idempotence of dismissal a query rather
 * than a constraint.
 *
 * <p>Hand-written {@code equals} and {@code hashCode} for the same reason as
 * {@link AnnouncementContentId}.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AnnouncementMarkerId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "announcement_id", nullable = false, updatable = false)
    private UUID announcementId;

    @Column(name = "owner_user_id", nullable = false, updatable = false, length = 255)
    private String ownerUserId;

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AnnouncementMarkerId that)) {
            return false;
        }
        return Objects.equals(announcementId, that.announcementId)
                && Objects.equals(ownerUserId, that.ownerUserId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(announcementId, ownerUserId);
    }
}
