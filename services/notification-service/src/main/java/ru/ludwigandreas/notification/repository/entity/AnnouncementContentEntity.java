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
 * One announcement's rendered body, in one language.
 *
 * <h2>Why the row count follows languages and never the audience</h2>
 *
 * <p>Keyed by announcement and locale, so a deployment supporting English and Russian stores two rows
 * per announcement whether it reaches ten people or a hundred thousand. That is what lets each
 * recipient read their own language without giving up the property the whole aggregate exists for -
 * one announcement is a constant number of rows.
 *
 * <p>Rendered at publish, and <b>every supported locale is rendered before anything is inserted</b>,
 * so a template that compiles in English and fails in Russian rejects the whole publish rather than
 * producing a half-translated announcement. Rendering at read time is not an option for the same
 * reason the inbox renders at fan-out: {@code retention.recipient-data-ttl} scrubs the variable map,
 * so an announcement in its third week could no longer be produced at all.
 *
 * <p>A recipient whose locale has no row gets the default locale's content in full - the same
 * fallback rule {@code TemplateCoordinates} already applies when resolving a template file, rather
 * than a second rule a reader would have to discover.
 *
 * <p>Correcting an announcement re-renders every locale together, so the languages cannot drift apart
 * into saying different things.
 *
 * <p>Extends {@link AbstractEntity} with the composite key as its id type rather than an audited
 * base class. {@code AbstractEntity} declares no {@code @Id} field of its own - only an abstract
 * {@code getId()} - which is precisely what makes a composite key expressible while still satisfying
 * the platform's entity-base rule. An audited base would duplicate the announcement's own author and
 * timestamps here and invite the two to disagree.
 */
@Entity
@Table(name = "notification_announcement_content")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnnouncementContentEntity extends AbstractEntity<AnnouncementContentId> {

    @EmbeddedId
    private AnnouncementContentId id;

    @Column(name = "subject", length = 998)
    private String subject;

    @Column(name = "body_html", columnDefinition = "text")
    private String bodyHtml;

    @Column(name = "body_text", columnDefinition = "text")
    private String bodyText;

    @Column(name = "rendered_at", nullable = false)
    private Instant renderedAt;
}
