package ru.ludwigandreas.idempotency.entity;

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
import ru.ludwigandreas.db.core.entity.GeneratedEntity;

/**
 * One claimed key, its state, and the response a duplicate is answered with.
 *
 * <p>The unique index on {@code (scope, idempotency_key)} is what actually enforces dedup - not this
 * class, and not the checking code around it. Two replicas handed the same at-least-once record both
 * run the same conditional upsert; the loser of the race blocks on the winner's row lock and reads the
 * committed winner back. No amount of read-then-write checking in Java achieves that, and the way it
 * fails is silent: under low load it looks perfect.
 *
 * <h2>Why it is one table for both claim modes</h2>
 *
 * <p>Two tables would mean two sets of columns that are the same except for three, two purges, two
 * reclaim rules and a decision at every read about which one to look in - and a key claimed
 * transactionally by a consumer and then presented to the HTTP surface would silently be two claims.
 * The columns a transactional claim does not use ({@code lease_expires_at} and the four response
 * columns) are simply null on its rows, and {@link ClaimState} says which mode a row came from without
 * needing a column for it.
 *
 * <h2>Why the response lives here and not in a second table</h2>
 *
 * <p>A separate {@code idempotency_response} table would be a second write on the completion path, a
 * join on every replay, and a row that can outlive or predate its claim. The response is one-to-one
 * with the claim, is written once, is read only through it, and dies with it - which is a column, not
 * a relationship.
 *
 * <p>It is deliberately <em>not</em> extending {@code AuditedEntity}. Nothing updates a claim through
 * the ORM: every write is one of the statements in {@code ru.ludwigandreas.idempotency.sql}, which
 * Hibernate never sees, so {@code @LastModifiedDate} would not fire and {@code updated_at} would report
 * the last ORM write rather than the last change - a timestamp that is wrong exactly when somebody is
 * trying to work out whether a claim is live.
 */
@Entity
@Table(name = "idempotency_claim")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdempotencyClaimEntity extends GeneratedEntity<UUID> {

    /**
     * What the key is unique within.
     *
     * <p>Scoped rather than global because a Kafka record key and an HTTP {@code Idempotency-Key} come
     * from different namespaces, and a collision between them would silently drop a genuine request.
     * The HTTP surface narrows it further, to one scope per endpoint, because a client that generates
     * one key per user action and calls two endpoints with it is not sending a duplicate.
     */
    @Column(name = "scope", nullable = false, updatable = false, length = 64)
    private String scope;

    /** The caller's key, verbatim. */
    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 255)
    private String idempotencyKey;

    /**
     * The id that owns the claim; returned verbatim to every later duplicate.
     *
     * <p>Updatable, unlike the scope and the key, because a reclaim rewrites it: a claim whose window
     * passed, whose holder's lease expired, or which was reported failed is taken over by the next
     * caller in the same statement that would otherwise have inserted it.
     */
    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    /** Where the claim is in its life. */
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private ClaimState state;

    /**
     * A hash of the request this key was used for, or null when the caller stored none.
     *
     * <p>A hash rather than the body: cheaper, and it keeps this table out of the set of places that
     * accumulate request payloads - which would otherwise need the redaction rules in
     * {@code audit-core} applied to it and an entitlement check on every reader.
     */
    @Column(name = "fingerprint", length = 64)
    private String fingerprint;

    /**
     * When an {@link ClaimState#IN_PROGRESS} claim stops being the holder's, absent a renewal.
     *
     * <p>Null on every other state. The single source of truth for "is this holder still working", with
     * no participation from the holder required - which is the only way a claim survives the process
     * holding it being killed.
     */
    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    /** Why a {@link ClaimState#FAILED} claim failed, for the operator reading the table. Never a payload. */
    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    /** The status of the response a duplicate replays, or null when there is nothing to replay. */
    @Column(name = "response_status")
    private Integer responseStatus;

    /** The replayed response's media type. */
    @Column(name = "response_content_type", length = 128)
    private String responseContentType;

    /**
     * The replayed response's headers, as a JSON object.
     *
     * <p>{@code text} and not {@code jsonb}, which is the opposite of the choice every other JSON
     * column in this platform makes, because the reason for {@code jsonb} does not apply: nothing ever
     * queries inside this value. It is written once by the filter and read once by the replay, as a
     * whole, and a {@code jsonb} column would buy indexing and containment operators nobody will use at
     * the cost of a parse and a rewrite on every completion.
     */
    @Column(name = "response_headers", columnDefinition = "text")
    private String responseHeaders;

    /**
     * The replayed response's body.
     *
     * <p>Bytes rather than text, so that a replay is byte-for-byte: re-encoding a body through a
     * {@code String} changes the length of anything outside ASCII, and a replay a client can tell from
     * the original is not a replay.
     */
    @Column(name = "response_body")
    private byte[] responseBody;

    /** When the claim was first taken, or last reclaimed. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * When the claim stops being recognised.
     *
     * <p>The window in which a retry is recognised, which makes it a correctness parameter rather than
     * housekeeping: shortening it converts duplicates into double executions, not into disk savings.
     * Keeping claims forever is the other failure - the table grows without limit and a caller may
     * never legitimately reuse a key.
     */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
