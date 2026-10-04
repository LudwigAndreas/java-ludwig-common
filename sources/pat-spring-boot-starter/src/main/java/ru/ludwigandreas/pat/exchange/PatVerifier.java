package ru.ludwigandreas.pat.exchange;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.cache.api.LudwigCache;
import ru.ludwigandreas.pat.audit.PatAuditEvents;
import ru.ludwigandreas.pat.cache.CachedVerification;
import ru.ludwigandreas.pat.entity.PatEntity;
import ru.ludwigandreas.pat.repository.PatQueryRepository;
import ru.ludwigandreas.pat.service.PatScopeCodec;
import ru.ludwigandreas.pat.service.PatUsageTracker;
import ru.ludwigandreas.pat.token.PatDigest;
import ru.ludwigandreas.pat.token.PatTokens;

/**
 * Turns a presented token into a verified identity, or refuses it.
 *
 * <p>The order of operations is the design, because each step is cheaper than the next and the endpoint is
 * the system's only brute-force target:
 *
 * <ol>
 *   <li><b>Parse and checksum</b> - no I/O at all. A flood of garbage costs CPU and nothing else, which is
 *       what stops it converting into database load.</li>
 *   <li><b>Cache lookup</b> on the secret's digest - no I/O in the common case.</li>
 *   <li><b>Indexed point read</b> on the key id, then a constant-time digest comparison in the
 *       application.</li>
 * </ol>
 *
 * <p>Every refusal throws {@link ExchangeRefusedException} with a reason that reaches metrics and audit and
 * never the response. The caller sees one identical body for all of them.
 *
 * <h2>Why the cache is consulted before the row, and what that costs</h2>
 *
 * <p>The cache holds a {@link CachedVerification} - a projection with no digest in it - keyed on the digest
 * of the presented secret. A hit therefore means "this exact secret verified recently", which is sound: the
 * key <em>is</em> the proof, so a hit cannot be obtained without presenting the secret.
 *
 * <p>What a hit skips is the usage tracker, because the signals it computes need the row's previous use and
 * a hit has no row. That is stated on {@link PatUsageTracker} and is why first use is always detected
 * (nothing is cached before the first verification) while an unseen source can be missed inside one cache
 * TTL.
 *
 * <p>A hit does <b>not</b> skip the expiry or audience checks. Both are re-evaluated from the cached
 * projection, because a token can expire while cached and because the audience differs per request.
 * Revocation is the one thing a hit cannot see, and the cache TTL is exactly the window that bounds it -
 * which is why the TTL is declared {@code CachePurpose.SECURITY} and ceilinged at startup.
 */
@RequiredArgsConstructor
public class PatVerifier {

    private final PatQueryRepository queries;

    private final LudwigCache<String, CachedVerification> cache;

    private final PatUsageTracker usageTracker;

    private final AuditSink auditSink;

    private final Clock clock;

    /**
     * Verifies a presented token for a requested audience.
     *
     * @param presented the raw {@code Authorization} value the edge forwarded
     * @param audience  the destination service, which must be one the token permits
     * @param sourceIp  where the request came from, for the allowlist and the usage signals
     * @return the verified projection
     * @throws ExchangeRefusedException for every failure, with the reason for metrics and audit only
     */
    public CachedVerification verify(String presented, String audience, String sourceIp) {
        PatTokens.ParsedToken parsed = PatTokens.parse(presented)
                .orElseThrow(() -> new ExchangeRefusedException(ExchangeFailure.MALFORMED));

        Instant now = clock.instant();

        Optional<CachedVerification> cached = cache.get(parsed.digest());
        if (cached.isPresent()) {
            return checkCached(cached.get(), audience, now);
        }

        PatEntity entity = queries.findByAnyKeyId(parsed.keyId())
                .orElseThrow(() -> new ExchangeRefusedException(ExchangeFailure.UNKNOWN_KEY));

        if (entity.getRevokedAt() != null) {
            // Audited, not merely counted. The uniform response tells the presenter nothing, which is
            // exactly why this has to reach the sink: something still holds a credential we revoked.
            auditSink.record(new PatAuditEvents.RevokedTokenPresented(
                    entity.getId().toString(), entity.getOwnerSubject(), sourceIp).toAuditEvent());
            throw new ExchangeRefusedException(ExchangeFailure.REVOKED);
        }
        if (!matchesEitherSecret(entity, parsed, now)) {
            throw new ExchangeRefusedException(ExchangeFailure.BAD_SECRET);
        }
        if (entity.getExpiresAt() != null && !entity.getExpiresAt().isAfter(now)) {
            throw new ExchangeRefusedException(ExchangeFailure.EXPIRED);
        }
        if (!CidrAllowlist.permits(PatScopeCodec.decode(entity.getAllowedCidrs()), sourceIp)) {
            throw new ExchangeRefusedException(ExchangeFailure.SOURCE_NOT_ALLOWED);
        }

        CachedVerification verification = new CachedVerification(
                entity.getId().toString(),
                entity.getOwnerSubject(),
                PatScopeCodec.decode(entity.getScopes()),
                PatScopeCodec.decode(entity.getAudiences()),
                entity.getExpiresAt());

        if (!verification.permits(audience)) {
            throw new ExchangeRefusedException(ExchangeFailure.AUDIENCE_NOT_PERMITTED);
        }

        // Cached only after every check has passed, so a refused token is never cached as verified.
        cache.put(parsed.digest(), verification);

        // The row was read, so the signals can be computed. Called after caching rather than before, so a
        // failure in telemetry cannot leave the cache unpopulated and make the next request read again.
        usageTracker.recordVerifiedUse(entity, sourceIp, audience);

        return verification;
    }

    /**
     * Re-checks what a cache hit can still get wrong.
     *
     * <p>Expiry, because a token can expire while cached - the TTL bounds revocation, not expiry. Audience,
     * because it is per request and a cached verification says nothing about where this particular request
     * was going. Revocation is deliberately absent: a hit cannot see it, and the TTL is the window.
     */
    private CachedVerification checkCached(CachedVerification verification, String audience, Instant now) {
        if (verification.isExpired(now)) {
            throw new ExchangeRefusedException(ExchangeFailure.EXPIRED);
        }
        if (!verification.permits(audience)) {
            throw new ExchangeRefusedException(ExchangeFailure.AUDIENCE_NOT_PERMITTED);
        }
        return verification;
    }

    /**
     * Whether the presented secret matches the current digest, or the superseded one inside its overlap.
     *
     * <p>Both, because during a rotation overlap a consumer may still be presenting the old secret and it
     * has to resolve to the same token. Constant-time on both comparisons - the second one is just as much
     * a secret as the first, and a fast path for the superseded digest would leak whether a token has been
     * rotated.
     */
    private boolean matchesEitherSecret(PatEntity entity, PatTokens.ParsedToken parsed, Instant now) {
        if (PatDigest.matches(parsed.digest(), entity.getSecretDigest())) {
            return true;
        }
        return entity.acceptsPreviousSecret(now)
                && PatDigest.matches(parsed.digest(), entity.getPreviousSecretDigest());
    }
}
