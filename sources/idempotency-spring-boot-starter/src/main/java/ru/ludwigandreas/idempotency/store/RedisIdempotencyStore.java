package ru.ludwigandreas.idempotency.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import ru.ludwigandreas.idempotency.api.ClaimMode;
import ru.ludwigandreas.idempotency.api.ClaimOutcome;
import ru.ludwigandreas.idempotency.api.ClaimRequest;
import ru.ludwigandreas.idempotency.api.ClaimResult;
import ru.ludwigandreas.idempotency.api.IdempotencyStore;
import ru.ludwigandreas.idempotency.api.StoredResponse;
import ru.ludwigandreas.idempotency.entity.ClaimState;
import ru.ludwigandreas.idempotency.error.UnsupportedClaimModeException;
import ru.ludwigandreas.idempotency.metrics.IdempotencyMetrics;

/**
 * {@link IdempotencyStore} over Redis, for {@link ClaimMode#STANDALONE} only.
 *
 * <h2>Read this before configuring it</h2>
 *
 * <p><b>A Redis claim is not transactional with the database write it protects.</b> That is not a
 * limitation of this implementation, it is what Redis is: the claim commits in a different system from
 * the work, so there is no arrangement of the two calls that makes them one atomic act.
 *
 * <p>Two consequences, and both are real rather than theoretical:
 *
 * <ul>
 *   <li>it <b>cannot serve {@link ClaimMode#TRANSACTIONAL}</b>, and says so by throwing rather than by
 *       degrading. Substituting a standalone claim there would leave keys claimed for work that rolled
 *       back - the precise failure that mode exists to prevent;</li>
 *   <li>a crash <b>between the claim and the commit</b> leaves the key claimed for work that never
 *       happened. The lease bounds the damage: the claim reverts to claimable when it expires, so the
 *       window is a lease rather than a TTL. It is still a window in which a caller's retry is told "in
 *       flight" about work nobody is doing.</li>
 * </ul>
 *
 * <p>That is acceptable for an HTTP filter in front of a handler that is itself idempotent, where the
 * worst case is a caller waiting out a lease. It is <b>not</b> acceptable for the consumer case, which is
 * the case the primitive was written for: an at-least-once record whose claim survived a rollback is a
 * message that will never be delivered, and nothing will say so.
 *
 * <p>This caveat is on the class and not only in the README on purpose. A team that reaches for Redis is
 * doing so for throughput, at the moment they are editing configuration, and the README is not what they
 * are reading.
 *
 * <h2>How the claim is atomic anyway</h2>
 *
 * <p>Within Redis it is: {@code SET key value NX PX ttl} either sets or does not, in one command, on one
 * thread. What it cannot do is tell the loser what the winner wrote in the same command, so the loser
 * reads the value back - and a read-after-failed-{@code SETNX} is safe here in a way the equivalent is
 * not in {@code READ COMMITTED} SQL, because the winner's write was already visible when the
 * {@code SETNX} was refused. There is no uncommitted state in Redis for the read to miss.
 */
@Slf4j
public class RedisIdempotencyStore implements IdempotencyStore {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final IdempotencyMetrics metrics;
    private final Clock clock;
    private final String keyPrefix;

    /**
     * Creates the store.
     *
     * @param redis        the template
     * @param objectMapper serialises the stored claim
     * @param metrics      what this module reports about itself
     * @param clock        the clock claims are judged against
     * @param keyPrefix    namespace for every key this store writes, so that a Redis shared with a cache
     *                     cannot have a cache eviction policy quietly delete claims
     */
    public RedisIdempotencyStore(StringRedisTemplate redis, ObjectMapper objectMapper,
                                 IdempotencyMetrics metrics, Clock clock, String keyPrefix) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.clock = clock;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public ClaimResult claim(ClaimRequest request) {
        if (request.mode() != ClaimMode.STANDALONE) {
            throw new UnsupportedClaimModeException(request.mode(), getClass());
        }
        Instant now = clock.instant();
        String redisKey = redisKey(request.scope(), request.key());
        StoredClaim fresh = new StoredClaim(request.requestId().toString(), ClaimState.IN_PROGRESS.name(),
                request.fingerprint(), now.plus(request.lease()).toEpochMilli(),
                now.plus(request.ttl()).toEpochMilli(), null, null, null, null);

        ValueOperations<String, String> values = redis.opsForValue();
        if (Boolean.TRUE.equals(values.setIfAbsent(redisKey, write(fresh), request.ttl()))) {
            return record(ClaimResult.claimed(request.requestId(), request.fingerprint(),
                    now.plus(request.ttl())), request);
        }

        StoredClaim held = read(values.get(redisKey));
        if (held == null || reclaimable(held, now)) {
            // The holder is gone, failed, or its value could not be read. SET without NX takes it over;
            // the race with another instance doing the same is resolved by whoever writes last, which is
            // the one weakening against the Postgres store: there, the loser blocks on a row lock and is
            // told it lost. Both callers here can believe they won a reclaimed key, so a reclaim is a
            // best-effort recovery from an abandoned claim rather than a second mutual exclusion.
            values.set(redisKey, write(fresh), request.ttl());
            log.debug("Reclaimed abandoned Redis claim {}", redisKey);
            return record(ClaimResult.claimed(request.requestId(), request.fingerprint(),
                    now.plus(request.ttl())), request);
        }
        return record(asResult(held), request);
    }

    @Override
    public Optional<ClaimResult> find(String scope, String key) {
        StoredClaim held = read(redis.opsForValue().get(redisKey(scope, key)));
        return held == null ? Optional.empty() : Optional.of(asResult(held));
    }

    @Override
    public boolean complete(String scope, String key, UUID requestId, StoredResponse response) {
        return rewrite(scope, key, requestId, held -> held.completed(response));
    }

    @Override
    public boolean fail(String scope, String key, UUID requestId, String reason) {
        return rewrite(scope, key, requestId, held -> held.failed());
    }

    @Override
    public boolean renewLease(String scope, String key, UUID requestId, Duration lease) {
        Instant until = clock.instant().plus(lease);
        return rewrite(scope, key, requestId, held -> held.leasedUntil(until.toEpochMilli()));
    }

    /**
     * Nothing to purge: Redis expires keys itself.
     *
     * <p>Which is the one axis on which this backend is simpler rather than weaker. It also means the TTL
     * is enforced by Redis rather than evaluated per claim, so a claim past its window is genuinely gone
     * instead of present-and-ignored.
     *
     * @param now unused; Redis judges expiry itself
     * @return zero, always
     */
    @Override
    public long purgeExpired(Instant now) {
        return 0L;
    }

    @Override
    public boolean supports(ClaimMode mode) {
        return mode == ClaimMode.STANDALONE;
    }

    /**
     * Rewrites a claim, if it is still the caller's.
     *
     * <p>Read, check the holder, write - which is not atomic, and does not need to be: the only writer of
     * a given key at a given moment is its holder, and a holder that is no longer the holder is refused
     * by the check. What this cannot detect is a lease that lapsed and was re-taken between the read and
     * the write, which is the same window the Postgres store closes with a conditional {@code UPDATE} and
     * this one cannot. It is bounded by the lease and is recorded here rather than hidden.
     */
    private boolean rewrite(String scope, String key, UUID requestId,
                            java.util.function.UnaryOperator<StoredClaim> change) {
        String redisKey = redisKey(scope, key);
        StoredClaim held = read(redis.opsForValue().get(redisKey));
        if (held == null || !requestId.toString().equals(held.requestId())) {
            return false;
        }
        Duration remaining = Duration.between(clock.instant(), Instant.ofEpochMilli(held.expiresAtMillis()));
        if (remaining.isNegative() || remaining.isZero()) {
            return false;
        }
        redis.opsForValue().set(redisKey, write(change.apply(held)), remaining);
        return true;
    }

    private static boolean reclaimable(StoredClaim held, Instant now) {
        if (ClaimState.FAILED.name().equals(held.state())) {
            return true;
        }
        return ClaimState.IN_PROGRESS.name().equals(held.state())
                && held.leaseExpiresAtMillis() <= now.toEpochMilli();
    }

    private ClaimResult record(ClaimResult result, ClaimRequest request) {
        metrics.claim(request.scope(), result.outcome().name());
        return result;
    }

    private ClaimResult asResult(StoredClaim held) {
        ClaimOutcome outcome = ClaimState.IN_PROGRESS.name().equals(held.state())
                ? ClaimOutcome.IN_PROGRESS
                : ClaimOutcome.COMPLETED;
        Instant retryAt = Instant.ofEpochMilli(outcome == ClaimOutcome.IN_PROGRESS
                ? held.leaseExpiresAtMillis() : held.expiresAtMillis());
        return new ClaimResult(outcome, UUID.fromString(held.requestId()), held.fingerprint(),
                held.response(), retryAt);
    }

    private String redisKey(String scope, String key) {
        return keyPrefix + ':' + scope + ':' + key;
    }

    private String write(StoredClaim claim) {
        try {
            return objectMapper.writeValueAsString(claim);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise an idempotency claim", e);
        }
    }

    /**
     * Reads a stored claim.
     *
     * <p>An unreadable value is treated as no claim, which errs towards executing the work twice rather
     * than never. That is the right direction for the backend whose documented weakness is already
     * "a claim may outlive work that did not happen": the failure this must not add is a key that can
     * never be used again because one byte of JSON was corrupted.
     */
    private StoredClaim read(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(stored, StoredClaim.class);
        } catch (JsonProcessingException e) {
            log.warn("Could not read a stored Redis claim; treating the key as unclaimed", e);
            return null;
        }
    }

    /**
     * A claim as it is stored in Redis.
     *
     * <p>One JSON value rather than a hash with fields, because every read and every write here is of the
     * whole claim - a partial update would need {@code WATCH}/{@code MULTI} to be safe and would buy
     * nothing, since the claim is at most a few hundred bytes plus the response.
     *
     * <p>Instants are epoch millis rather than ISO strings: this value is written on every request on a
     * protected endpoint, and a numeric comparison in a reclaim check that runs on that path should not
     * involve parsing a date.
     *
     * @param requestId            the holder
     * @param state                the {@code ClaimState} name
     * @param fingerprint          the request fingerprint, or null
     * @param leaseExpiresAtMillis when the holder stops being the holder absent a renewal
     * @param expiresAtMillis      when the claim stops being recognised
     * @param responseStatus       the stored response's status, or null
     * @param responseContentType  the stored response's media type, or null
     * @param responseHeaders      the stored response's headers
     * @param responseBodyBase64   the stored response's body, Base64 because JSON has no byte string and
     *                             a response body is not necessarily text
     */
    record StoredClaim(String requestId, String state, String fingerprint, long leaseExpiresAtMillis,
                       long expiresAtMillis, Integer responseStatus, String responseContentType,
                       Map<String, String> responseHeaders, String responseBodyBase64) {

        /** This claim, completed with {@code response}. */
        StoredClaim completed(StoredResponse response) {
            if (response == null) {
                return new StoredClaim(requestId, ClaimState.COMPLETED.name(), fingerprint,
                        leaseExpiresAtMillis, expiresAtMillis, null, null, null, null);
            }
            return new StoredClaim(requestId, ClaimState.COMPLETED.name(), fingerprint,
                    leaseExpiresAtMillis, expiresAtMillis, response.status(), response.contentType(),
                    new LinkedHashMap<>(response.headers()),
                    Base64.getEncoder().encodeToString(response.body()));
        }

        /** This claim, failed and therefore immediately reclaimable. */
        StoredClaim failed() {
            return new StoredClaim(requestId, ClaimState.FAILED.name(), fingerprint, leaseExpiresAtMillis,
                    expiresAtMillis, null, null, null, null);
        }

        /** This claim, leased until {@code millis}. */
        StoredClaim leasedUntil(long millis) {
            return new StoredClaim(requestId, state, fingerprint, millis, expiresAtMillis, responseStatus,
                    responseContentType, responseHeaders, responseBodyBase64);
        }

        /** The stored response, or null when there is none. */
        StoredResponse response() {
            if (responseStatus == null) {
                return null;
            }
            byte[] body = responseBodyBase64 == null
                    ? new byte[0] : Base64.getDecoder().decode(responseBodyBase64);
            return new StoredResponse(responseStatus, responseContentType,
                    responseHeaders == null ? Map.of() : responseHeaders, body);
        }
    }
}
