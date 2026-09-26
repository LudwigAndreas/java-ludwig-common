package ru.ludwigandreas.idempotency.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import ru.ludwigandreas.idempotency.entity.IdempotencyClaimEntity;

/**
 * Everything about a claim that is not the claim itself.
 *
 * <p>The claim is one conditional upsert and lives in {@code ru.ludwigandreas.idempotency.sql}, which
 * is the module's one carve-out from the QueryDSL-only rule. Every method here is a QueryDSL expression
 * against the generated Q-type, so a renamed or retyped column breaks the compile rather than a request
 * during an incident.
 *
 * <p>The three state transitions are bulk updates conditional on the caller still being the holder -
 * the same fencing {@code RunLockHandle#renew} does, and for the same reason: an instance whose lease
 * expired while it was working is no longer authoritative, and applying its completion would publish a
 * response for work another instance is in the middle of repeating.
 */
public interface IdempotencyClaimQueryRepository {

    /** The live claim on this key, if there is one. A claim past its window is not a claim. */
    Optional<IdempotencyClaimEntity> findActive(String scope, String key, Instant now);

    /** The claim on this key whatever its state, for an operator endpoint and for the tests. */
    Optional<IdempotencyClaimEntity> findAny(String scope, String key);

    /**
     * Marks a claim completed and stores the response a duplicate will replay.
     *
     * @return whether the claim was still {@code requestId}'s
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - five values of one stored response, which is assembled by
    // the caller and would gain nothing from being re-wrapped for one call.
    boolean complete(String scope, String key, UUID requestId, Integer status, String contentType,
                     String headers, byte[] body);

    /**
     * Marks a claim failed, freeing the key immediately.
     *
     * @return whether the claim was still {@code requestId}'s
     */
    boolean fail(String scope, String key, UUID requestId, String reason);

    /**
     * Extends an in-progress claim's lease.
     *
     * @return whether the claim is still {@code requestId}'s and still in progress
     */
    boolean renewLease(String scope, String key, UUID requestId, Instant leaseUntil, Instant now);

    /** Drops claims past their window, freeing the keys for reuse. Runs under the distributed lock. */
    long purgeExpired(Instant now, int batchSize);

    /** How many claims there are in a state, for the purge's log line and for an operator. */
    long countExpired(Instant now);
}
