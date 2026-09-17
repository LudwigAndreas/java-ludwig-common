package ru.ludwigandreas.notification.repository;

import java.time.Instant;

/** The two halves of lock ownership that are plain, unconditional writes. */
public interface DistributedLockQueryRepository {

    /**
     * Extends the lease, but only for the instance that still holds it.
     *
     * <p>The owner is part of the predicate rather than assumed: a job that stalled long enough to
     * lose its lock must not be able to renew it out from under whichever replica has since taken
     * over. A return of 0 is the signal to stop working, not to retry.
     *
     * @return 1 when this instance still held the lock, 0 when it did not
     */
    long renew(String name, String owner, Instant expiresAt);

    /** Releases the lock, again only if this instance holds it. */
    long release(String name, String owner);
}
