package ru.ludwigandreas.cache.tx;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs an eviction <em>after</em> the transaction that caused the change commits.
 *
 * <h2>Why not simply evict where the write happens</h2>
 *
 * <p>Evicting inside the transaction is the obvious implementation and it is wrong in both directions.
 *
 * <ul>
 *   <li>It opens a window in which another thread misses, re-reads the row as it was <em>before</em> the
 *       uncommitted change, and repopulates the cache with the stale value - which then survives until
 *       the TTL, long after the write everybody believes took effect. The eager eviction does not just
 *       fail to help here; it actively creates a stale entry that would not otherwise exist.</li>
 *   <li>If the transaction subsequently rolls back, the eviction has still happened, so a correct
 *       cached value was discarded for nothing. That one is merely wasteful, which is why the rollback
 *       case is the one the tests pin: evicting eagerly on a commit is correct-but-racy, evicting on a
 *       rollback caches nothing and hides a bug.</li>
 * </ul>
 *
 * <h2>Why this is in the cache module</h2>
 *
 * <p>It was written twice before this module existed: once as {@code user-settings}' own
 * {@code AfterCommitEviction} with four call sites, and once inline in
 * {@code IdentityProjectionService}, which registered its own {@link TransactionSynchronization} for the
 * same purpose in the same shape. The second copy is how a helper becomes a convention nobody can find.
 *
 * <p>Deliberately static and stateless. {@link TransactionSynchronizationManager} is thread-bound state,
 * so there is nothing for an instance to hold, and a bean would mean every caller needing the eviction
 * would also need an injection point for it - which is the friction that produced the inline copy.
 *
 * <h2>No active transaction</h2>
 *
 * <p>The eviction happens immediately. That is correct rather than a fallback: with no transaction there
 * is nothing to wait for and nothing that could roll back.
 */
public final class AfterCommitEviction {

    private AfterCommitEviction() {
    }

    /**
     * Registers {@code eviction} to run once the current transaction commits, or runs it now if there
     * is no transaction.
     *
     * <p>The eviction must not throw in a way the caller depends on: it runs in {@code afterCommit},
     * where the business transaction is already durable and nothing can be undone. Whether a failed
     * eviction is loud is the eviction's own business - the shared tier counts and logs its failures,
     * because a shared eviction that silently did not happen leaves every replica stale.
     *
     * @param eviction what to run after commit
     */
    public static void run(Runnable eviction) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            eviction.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                eviction.run();
            }
        });
    }
}
