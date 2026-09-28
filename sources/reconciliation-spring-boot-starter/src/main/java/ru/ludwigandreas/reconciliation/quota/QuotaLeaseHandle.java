package ru.ludwigandreas.reconciliation.quota;

import java.util.UUID;

/**
 * A held slot of a partner-scoped quota.
 *
 * <p>Renewal is not optional. The lease expires unless the holder keeps saying it is still working,
 * and a holder whose renewal returns {@code false} has lost the slot: another instance may already
 * have reclaimed it and started work in its place, so continuing would put more work at the partner
 * than the quota allows.
 */
public interface QuotaLeaseHandle extends AutoCloseable {

    /** The lease row's id, stored on the remote job so a new owner can adopt it. */
    UUID leaseId();

    /** The quota this slot belongs to. */
    String quotaName();

    /**
     * Extends the lease.
     *
     * @return {@code true} if it is still held by this instance; {@code false} if it has been lost,
     *         in which case the caller must stop
     */
    boolean renew();

    /** Releases the slot. Idempotent; releasing a lease already lost is a no-op. */
    @Override
    void close();
}
