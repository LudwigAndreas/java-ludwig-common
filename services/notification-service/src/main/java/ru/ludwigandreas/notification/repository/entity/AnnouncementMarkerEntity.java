package ru.ludwigandreas.notification.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.ludwigandreas.db.core.entity.AbstractEntity;

/**
 * One recipient's dismissal of one announcement.
 *
 * <h2>Lazy, which is what keeps the aggregate's cost independent of audience size</h2>
 *
 * <p>Written on first dismissal and <b>never in advance</b>. An announcement nobody dismissed has no
 * rows here at all; one that half the estate dismissed has half as many as a per-recipient fan-out
 * would have created before anybody had read it. The absence of a row is the normal state and means
 * "not dismissed", which is why the read path asks {@code NOT EXISTS} rather than joining.
 *
 * <h2>There is deliberately no seen or read instant</h2>
 *
 * <p>The inbox item has three, because an item is a document somebody works through and "shown in a
 * list" is genuinely different from "opened". An announcement is either in your way or it is not.
 * A column with no consumer is one somebody later writes a query against, and adding "seen" here
 * would also mean writing a row for every recipient who merely loaded the page - which is exactly
 * the per-recipient cost this design avoids.
 *
 * <p>Dismissing is not deleting: the announcement stays visible to everybody else, and the dismisser
 * can still fetch it by identifier, because a client may hold a link to something it has just
 * dismissed.
 */
@Entity
@Table(name = "notification_announcement_marker")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnnouncementMarkerEntity extends AbstractEntity<AnnouncementMarkerId> {

    @EmbeddedId
    private AnnouncementMarkerId id;

    /** Set once and never reset, so a repeated dismissal cannot rewrite when it happened. */
    @Column(name = "dismissed_at", nullable = false)
    private Instant dismissedAt;
}
