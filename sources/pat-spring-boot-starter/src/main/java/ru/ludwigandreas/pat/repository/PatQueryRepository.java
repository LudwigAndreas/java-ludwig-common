package ru.ludwigandreas.pat.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
