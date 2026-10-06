package ru.ludwigandreas.pat.cache;

import java.io.Serializable;
import java.time.Instant;
import java.util.Set;

/**
 * What a successful verification yields, in the form that is safe to cache.
 *
 * <p>Deliberately not the entity. Three reasons, and the first is the one that matters: an entity carries
 * the secret digests, and a cached value may reach a shared Redis tier, a heap dump and an operator's cache
 * inspector. Caching a projection that holds no digest means the cache cannot leak one.
 *
 * <p>The other two: a detached JPA entity in a cache is a lifecycle hazard - it looks live, is not managed,
 * and lazy access throws at a point unrelated to where it was cached. And the entity will grow columns that
 * verification does not need, each of which would then be serialized on every cache write.
 *
 * @param patId        the token's stable id, which goes into the claim and every audit record
 * @param ownerSubject whose authority this token attenuates
 * @param scopes       the attenuation, which the claim carries and each service intersects
 * @param audiences    where this token may be presented - checked at exchange, enforced per service by the
 *                     existing {@code AudienceValidator}
 * @param expiresAt    when the token stops working, or {@code null} for a deployment that has explicitly
 *                     enabled non-expiring tokens
 */
public record CachedVerification(
        String patId,
        String ownerSubject,
        Set<String> scopes,
        Set<String> audiences,
        Instant expiresAt) implements Serializable {

    private static final long serialVersionUID = 1L;

    public CachedVerification {
        scopes = Set.copyOf(scopes);
        audiences = Set.copyOf(audiences);
    }

    /**
     * Whether this token has expired.
     *
     * <p>Checked against the cached {@code expiresAt} rather than only at the database read, because a
     * token can expire <em>while cached</em>: the cache TTL bounds how long a revocation takes to apply, and
     * an expiry that falls inside that window would otherwise keep working until the entry aged out. The
     * cache holds a verification, not a permission.
     */
    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    /** Whether this token may be presented for the requested audience. */
    public boolean permits(String audience) {
        return audience != null && audiences.contains(audience);
    }
}
