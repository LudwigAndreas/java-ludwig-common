package ru.ludwigandreas.usersettings.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import ru.ludwigandreas.odatafilter.annotation.FilterPolicy;
import ru.ludwigandreas.odatafilter.annotation.Filterable;
import lombok.Getter;
import lombok.Setter;
import ru.ludwigandreas.db.core.entity.SnapshotEntity;
import ru.ludwigandreas.usersettings.api.ConsentDecision;

/**
 * One consent decision, recorded forever.
 *
 * <p>Extends {@code SnapshotEntity} so that db-core's {@code SnapshotImmutabilityListener} rejects
 * any {@code @PreUpdate} at the persistence layer. That is the guarantee the whole design rests on:
 * revocation is a new row, correcting a mistake is a new row, and there is no code path - including
 * one written later by someone who has not read this - that can quietly change what a person is
 * recorded as having agreed to.
 *
 * <p>The {@code SnapshotEntity} family is documented as modelling data received from an outer system,
 * which a consent granted through this service's own API is not. The fit is imperfect and taken
 * deliberately: the immutability listener is exactly the behaviour required, and the provenance
 * columns are meaningful here in a way they are not for an audit row -
 * {@code sourceSystem} records which deployment recorded the decision ({@code self} in owner mode,
 * the upstream topic in a projection), which matters precisely because a projection holds consent
 * evidence it did not itself witness.
 *
 * <h2>What is recorded, and why each part</h2>
 *
 * <ul>
 *   <li>{@link #getTextVersion()} - the version of the wording agreed to. Without it, "did they
 *       accept?" is answerable and "what did they accept?" is not, and only the second one is a
 *       defence. A boolean column would have made the record worthless the first time the text was
 *       reworded.</li>
 *   <li>{@link #getLocale()} - which translation was shown. A consent given against a translation
 *       that turned out to say something different is a fact someone will eventually need.</li>
 *   <li>{@link #getOccurredAt()} - when the person decided, as opposed to when the row was written.
 *       These differ in a projection, and the first is the one with legal meaning.</li>
 *   <li>{@link #getEvidenceIp()}, {@link #getEvidenceUserAgent()}, {@link #getCorrelationId()} -
 *       the request context at the moment of the decision, which is what turns a claim into
 *       evidence.</li>
 * </ul>
 *
 * <p>The primary key is assigned by the owner, not generated per database. A projection inserts the
 * owner's id verbatim, so a redelivered event collides on the primary key instead of appending a
 * second copy of the same decision - idempotence by construction rather than by a de-duplication
 * table.
 */
@Getter
@Setter
@Entity
/*
 * Searchable by who, which consent, which version and when - the questions a compliance request
 * actually asks. The evidence fields (ip, user agent) and the correlation id carry no @Filterable
 * and must not be given one: they are personal data collected for one purpose, and a filterable
 * evidence_ip turns the consent ledger into a way to ask "which of our users were at this address".
 */
@FilterPolicy(maxDepth = 3, maxPageSize = 200, defaultPageSize = 50)
@Table(name = "user_consent",
        indexes = {
                @Index(name = "idx_user_consent_subject",
                        columnList = "tenant_id, subject, consent_key, occurred_at"),
                @Index(name = "idx_user_consent_occurred", columnList = "occurred_at")
        })
public class UserConsentEntity extends SnapshotEntity<UUID> {

    @Filterable
    @Column(name = "tenant_id", nullable = false, length = 128)
    private String tenantId;

    @Filterable
    @Column(name = "subject", nullable = false, length = 255)
    private String subject;

    /** Which consent this is about - {@code marketing.email}, {@code terms-of-service}. */
    @Filterable
    @Column(name = "consent_key", nullable = false, length = 128)
    private String consentKey;

    /** The version of the wording that was shown and agreed to. */
    @Filterable
    @Column(name = "text_version", nullable = false, length = 64)
    private String textVersion;

    @Filterable
    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 16)
    private ConsentDecision decision;

    /** Which translation of the text was presented, as a BCP 47 tag. */
    @Filterable
    @Column(name = "locale", length = 35)
    private String locale;

    /** When the person decided. The ordering key for the history, and for stale-event rejection. */
    @Filterable
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** Who recorded the decision - the subject themselves, or an operator acting on their behalf. */
    @Filterable
    @Column(name = "actor", nullable = false, length = 255)
    private String actor;

    @Column(name = "evidence_ip", length = 64)
    private String evidenceIp;

    @Column(name = "evidence_user_agent", length = 512)
    private String evidenceUserAgent;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    public boolean isGranted() {
        return decision == ConsentDecision.GRANTED;
    }
}
