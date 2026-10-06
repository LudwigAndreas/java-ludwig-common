package ru.ludwigandreas.pat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import ru.ludwigandreas.db.core.entity.AuditedEntity;

/**
 * One personal access token, as stored.
 *
 * <p>Holds <b>digests, never secrets</b>. The raw secret exists for the duration of the issuance response
 * and is then unreachable: there is no column for it, no field for it, and no code path that can produce it
 * from this row.
 *
 * <h2>Why the digests are nullable, and what the null means</h2>
 *
 * <p>A revoked or expired token keeps its row with {@link #secretDigest} <b>cleared</b>. That is not a
 * tidiness measure - it is how the record stays useful to an auditor while ceasing to be a credential. The
 * row still says who owned the token, what it could do and where it could be used, which is what an
 * investigation needs; and it can no longer authenticate anything, because the digest it would be compared
 * against is gone.
 *
 * <p>{@code PatDigest.matches} is null-safe and returns false for a null stored digest, so a cleared row
 * fails verification as an ordinary refusal rather than as an exception on the hot path.
 *
 * <h2>Why rotation is two key ids on one row</h2>
 *
 * <p>{@link #previousKeyId} and {@link #previousSecretDigest} hold the superseded secret until
 * {@link #previousSecretExpiresAt}. A rotation that invalidated the old secret immediately would require
 * every consumer of the token to be updated atomically; nothing real can do that, so the observed outcome is
 * that nobody rotates at all.
 *
 * <p>Both pairs live on the <b>same row</b> rather than in a second row, which is what keeps the token's
 * identity, scopes, audiences and audit history continuous across a rotation. A second row would be a second
 * token wearing the first one's name, with its own id in every audit record written after the rotation.
 */
@Entity
@Table(name = "ludwig_pat")
@Getter
@Setter
public class PatEntity extends AuditedEntity<UUID> {

    /**
     * The owner's stable subject - the OIDC {@code sub}, never a username or an email.
     *
     * <p>The same rule {@code LudwigPrincipal.subject} states and for the same reason: usernames and email
     * addresses get reassigned, and a credential keyed on a reassigned identifier attributes one person's
     * tokens to another. For a credential that outlives several of its owner's job titles, this matters more
     * than it does for a request-scoped principal.
     */
    @Column(name = "owner_subject", nullable = false)
    private String ownerSubject;

    /** The owner's own label, so somebody holding six tokens can tell them apart. Never authoritative. */
    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /**
     * The indexed lookup key for the current secret. Unique.
     *
     * <p>Secret-adjacent rather than secret: it is not confidential, but it travels with the secret and
     * changes on rotation. That is why the JWT claim and every audit record name {@code id} instead - an
     * identifier that survives rotation is the one an operator can act on.
     */
    @Column(name = "key_id", nullable = false, length = 64)
    private String keyId;

    /** SHA-256 of the current secret. Cleared on revocation and on expiry. */
    @Column(name = "secret_digest", length = 64)
    private String secretDigest;

    /** The superseded key id during a rotation overlap. Null for a token that has never been rotated. */
    @Column(name = "previous_key_id", length = 64)
    private String previousKeyId;

    /** SHA-256 of the superseded secret. Cleared when the overlap elapses. */
    @Column(name = "previous_secret_digest", length = 64)
    private String previousSecretDigest;

    /** When the superseded secret stops being accepted. */
    @Column(name = "previous_secret_expires_at")
    private Instant previousSecretExpiresAt;

    /**
     * The attenuation, as a delimited list.
     *
     * <p>Stored as text rather than in a join table on purpose. A scope set is read as a whole on every
     * verification and is never queried by element, so a join table would add a second read to the hot path
     * to support a query nobody makes. {@code PatScopeCodec} owns the encoding; nothing else parses it.
     */
    @Column(name = "scopes", nullable = false, columnDefinition = "text")
    private String scopes;

    /**
     * The permitted audiences, as a delimited list.
     *
     * <p>A separate dimension from the scopes, and they must not be collapsed: scope answers <b>what</b> may
     * be done, audience answers <b>where</b> it may be presented. Collapsing them is how a CI token issued
     * for deployments becomes presentable at the billing service - which the attenuation invariant keeps
     * from being privilege escalation, but for an owner holding broad roles "bounded by the owner" is not a
     * meaningful bound.
     */
    @Column(name = "audiences", nullable = false, columnDefinition = "text")
    private String audiences;

    /** Optional CIDR allowlist. Null or empty means the token may be presented from anywhere. */
    @Column(name = "allowed_cidrs", columnDefinition = "text")
    private String allowedCidrs;

    /**
     * When the token stops working.
     *
     * <p>Nullable in the column so a deployment that has explicitly enabled non-expiring tokens can store
     * one, but the service refuses a null unless {@code ludwig.pat.allow-non-expiring} is set. A credential
     * with no expiry is a credential that outlives every process that knows it exists.
     */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /** When an operator or the owner-disabled listener revoked it. Null while live. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    /**
     * Why it was revoked.
     *
     * <p>Recorded because the reasons lead to different actions. An operator's deliberate revocation, an
     * owner being disabled, and a rotation overlap elapsing are three different events, and an auditor
     * reading a list of revoked tokens needs to tell a departure from an incident response.
     */
    @Column(name = "revocation_reason", length = 64)
    private String revocationReason;

    /** Debounced, best-effort, and may be stale or absent. See the tracker for why that is acceptable. */
    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    /** Debounced, best-effort. Used to detect a first use from a source never seen for this token. */
    @Column(name = "last_used_ip", length = 64)
    private String lastUsedIp;

    /** Monotonic, debounced. Zero means never used, which is what makes a first use detectable. */
    @Column(name = "use_count", nullable = false)
    private long useCount;

    /** Whether this token can still authenticate anything at the given instant. */
    public boolean isLive(Instant now) {
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }

    /** Whether the superseded secret is still inside its overlap window. */
    public boolean acceptsPreviousSecret(Instant now) {
        return previousSecretDigest != null
                && previousSecretExpiresAt != null
                && previousSecretExpiresAt.isAfter(now);
    }

    /**
     * Destroys both digests, leaving the rest of the row readable.
     *
     * <p>The operation that makes a revoked token's record an audit artifact rather than a credential. Named
     * for what it does to the secrets rather than for the lifecycle event, because three different events -
     * revocation, expiry and the end of a rotation overlap - all need it.
     */
    public void destroySecrets() {
        this.secretDigest = null;
        this.previousSecretDigest = null;
        this.previousKeyId = null;
        this.previousSecretExpiresAt = null;
    }
}
