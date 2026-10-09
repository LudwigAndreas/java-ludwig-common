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
 * The composite key of an announcement's rendered content: the announcement and the language.
 *
 * <p>A composite key rather than a surrogate id with a unique constraint, because the pair <em>is</em>
 * the identity - there is exactly one rendering of one announcement in one language, and a surrogate
 * would allow a second row that the application would then have to choose between.
 *
 * <p>Hand-written {@code equals} and {@code hashCode} rather than Lombok's, because an embedded id is
 * used as a map key by the persistence context and Lombok's generated versions on a mutable class are
 * the usual source of the bug where an entity cannot be found after its key field is set.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AnnouncementContentId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "announcement_id", nullable = false, updatable = false)
    private UUID announcementId;

    @Column(name = "locale", nullable = false, updatable = false, length = 35)
    private String locale;

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AnnouncementContentId that)) {
            return false;
        }
        return Objects.equals(announcementId, that.announcementId)
                && Objects.equals(locale, that.locale);
    }

    @Override
    public int hashCode() {
        return Objects.hash(announcementId, locale);
    }
}
