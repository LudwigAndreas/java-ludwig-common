package ru.ludwigandreas.notification.service.idempotency;

import java.util.UUID;

/**
 * Claims a caller-supplied key so the same ask cannot be acted on twice.
 *
 * <h2>Why this exists here</h2>
 *
 * <p>This is one of the two capabilities the platform does not have yet, and without it this service
 * double-sends the moment it runs at more than one replica. The Kafka consumer is at-least-once by
 * construction, and REST callers retry on a timeout that may well have been a successful write - so
 * "did I already do this?" is not an edge case, it is the normal path during any rebalance, any
 * deploy and any network blip.
 *
 * <p>Deliberately narrow: three methods, no expiry policy in the signature, nothing about how it is
 * stored. That is what makes it promotable - see the README's "Promotion candidates" section. A
 * platform starter offering this interface backed by Postgres, and later by Redis for services that
 * would rather not, would be a drop-in replacement here.
 *
 * <h2>What an implementation must guarantee</h2>
 *
 * <p>{@link #claim} has to be atomic against concurrent callers in <em>different processes</em>, not
 * merely different threads. A read-then-write implementation satisfies the interface and fails the
 * requirement, and it fails it silently - under low load it looks perfect.
 */
public interface IdempotencyStore {

    /**
     * Claims {@code key} within {@code scope} for {@code requestId}, or reports the existing owner.
     *
     * @return the id that owns the key. Equal to {@code requestId} when this call won the claim;
     *         anything else means a duplicate and the caller must not act
     */
    UUID claim(String scope, String key, UUID requestId);

    /** Whether the key is currently claimed, for a read-only pre-check. */
    boolean isClaimed(String scope, String key);

    /**
     * Drops claims past their retention window.
     *
     * <p>Must run on exactly one replica - it is one of the jobs the distributed lock exists for.
     *
     * @return how many claims were released
     */
    long purgeExpired();
}
