package ru.ludwigandreas.job.core.lock;

import java.time.Duration;
import java.util.UUID;

/**
 * A held {@link RunLock} lease. Always used with try-with-resources: a handle that is not closed
 * keeps the lease until it expires, which delays every other instance's next run by up to the
 * lease TTL for no reason.
 */
public interface RunLockHandle extends AutoCloseable {

    /** The name this lease was taken on. */
    String lockName();

    /** The instance identity recorded as the holder. */
    String owner();

    /**
     * Identifies this particular acquisition.
     *
     * <p>Distinct from the owner: the same instance acquiring the same lease twice in a row produces
     * two run ids. Stamping it onto the rows a run touches is what lets an operator ask "which run
     * wrote this?" and what lets a renewal detect that the lease was stolen and re-taken in between.
     */
    UUID runId();

    /**
     * Extends the lease by another {@code leaseTtl} from now.
     *
     * @param leaseTtl the new time-to-live
     * @return {@code true} if the lease is still held by this acquisition; {@code false} if it
     *         expired and was taken by someone else, in which case the caller must stop working -
     *         another instance is already redoing this run
     */
    boolean renew(Duration leaseTtl);

    /** Releases the lease immediately. Idempotent; a release of a lease already lost is a no-op. */
    @Override
    void close();
}
