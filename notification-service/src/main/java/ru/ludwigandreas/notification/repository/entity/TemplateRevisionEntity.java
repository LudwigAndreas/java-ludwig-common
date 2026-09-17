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
import ru.ludwigandreas.db.core.entity.GeneratedEntity;

/**
 * A version of one template, identified by the hash of the source that produced a render.
 *
 * <p>Templates are files, hot-reloaded from a directory without a redeploy, which is what makes a
 * copywriting change a five-minute job instead of a release. The cost is that the directory is not a
 * historical record: by the time somebody asks "what wording did we send this customer in March?",
 * the file has been edited twice.
 *
 * <p>This table is the record. Every render hashes the template source it used and records the hash
 * on the delivery; the first time a hash is seen for a template name, a row appears here with the
 * next revision number and the source itself. So a delivery is always traceable to exact text, the
 * numbering is human-readable, and no version has to be declared by hand or remembered by anyone.
 */
@Entity
@Table(name = "notification_template_revision")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TemplateRevisionEntity extends GeneratedEntity<UUID> {

    /** Full template name as resolved, e.g. {@code welcome/email/ru/body.html.ftl}. */
    @Column(name = "template_name", nullable = false, updatable = false, length = 512)
    private String templateName;

    /** SHA-256 of the source, hex-encoded. Unique together with the name. */
    @Column(name = "content_hash", nullable = false, updatable = false, length = 64)
    private String contentHash;

    /** 1-based, per template name, in first-seen order. What an operator quotes. */
    @Column(name = "revision", nullable = false, updatable = false)
    private int revision;

    /** The source as rendered. Not personal data - it is the template, not the message. */
    @Column(name = "source", nullable = false, updatable = false, columnDefinition = "text")
    private String source;

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;
}
