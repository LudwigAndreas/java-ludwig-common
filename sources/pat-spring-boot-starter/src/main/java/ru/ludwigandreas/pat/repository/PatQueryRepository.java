package ru.ludwigandreas.pat.repository;

import com.querydsl.core.types.Predicate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ru.ludwigandreas.pat.entity.PatEntity;

/**
 * The reads, as QueryDSL predicates.
 *
 * <p>Separate from {@link PatRepository} for the reason the platform's other modules separate them: Spring
 * Data owns the CRUD plumbing, and the queries that carry actual decisions live somewhere a reader can find
 * them all at once.
 */
public interface PatQueryRepository {

    /**
     * The token a presented key id belongs to, matching either the current or the superseded key id.
     *
     * <p><b>This is the hot path and it is a point read.</b> Both key ids are uniquely indexed, so this is
     * an index lookup rather than a scan - which is what the key id exists for. Without it, verification
     * would be "load the candidate rows and compare digests", which is how a system ends up running a
     * digest per row and then reaching for a cache to hide it.
     *
     * <p>Matches the superseded key id too, which is what makes a rotation overlap work: during the overlap
     * a consumer may still be presenting the old secret, and it has to resolve to the same token.
     *
     * <p>Returns the row regardless of whether the token is live, revoked or expired. The caller decides -
     * because the caller is the one that must produce the same uniform failure for all three, and a query
     * that filtered them out would make "revoked" indistinguishable from "never existed" <em>in the
     * metrics</em>, which is where the defender needs them distinct.
     */
    Optional<PatEntity> findByAnyKeyId(String keyId);

    /** An owner's tokens, newest first, for the management API's list endpoint. */
    List<PatEntity> findByOwner(String ownerSubject);

    /**
     * Any owner's tokens, newest first, after a keyset position, for the administrative listing.
     *
     * @param filter         the caller's parsed {@code $filter}, or null for every token
     * @param afterCreatedAt the last returned token's creation time, or null
     * @param afterId        the last returned token's id, or null
     * @param limit          at most this many
     * @return the tokens
     */
    List<PatEntity> findForAdministration(Predicate filter, Instant afterCreatedAt, UUID afterId, int limit);

    /**
     * Records one verified use: the last-use time and source, and one more use.
     *
     * <p>Touches those three columns and nothing else, and not the entity version. The write is telemetry, run
     * asynchronously after an exchange; as a read-modify-save it bumped the version, so a revoke or rotation that
     * had read the row a moment earlier failed its own save - an operator's revoke answering 500 while the token
     * kept working. Forward-only on the time, so a late write never moves the last use backwards.
     *
     * @param id       the token
     * @param usedAt   when it was used
     * @param sourceIp where it was used from
     * @return the rows updated: 1, or 0 when the token is gone or a later use is already recorded
     */
    long recordUse(UUID id, Instant usedAt, String sourceIp);

    /** Live tokens whose expiry has passed, for the expiry sweep that clears their digests. */
    List<PatEntity> findExpiredButNotYetDestroyed(Instant now, int limit);

    /** Tokens whose rotation overlap has elapsed, so the superseded digest can be dropped. */
    List<PatEntity> findWithElapsedRotationOverlap(Instant now, int limit);

    /** Records past the retention period, for the purge. */
    List<PatEntity> findPurgeable(Instant purgeBefore, int limit);

    /** Live tokens belonging to an owner, for the owner-disabled revocation. */
    List<PatEntity> findLiveByOwner(String ownerSubject, Instant now);

    /**
     * Live tokens unused since the cutoff, for inactivity expiry.
     *
     * <p>Falls back to {@code createdAt} where {@code lastUsedAt} is null, because a token that has never
     * been used has no last use - and "minted six months ago and never used" is at least as strong a signal
     * as "not used recently".
     */
    List<PatEntity> findInactive(Instant cutoff, int limit);
}
