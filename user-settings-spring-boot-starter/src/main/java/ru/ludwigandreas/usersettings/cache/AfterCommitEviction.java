package ru.ludwigandreas.usersettings.cache;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.ludwigandreas.usersettings.api.SettingsSubject;

/**
 * Evicts the settings cache <em>after</em> the transaction that caused the change commits.
 *
 * <p>Evicting inside the transaction is the obvious implementation and it is wrong in both
 * directions. It opens a window in which another thread misses, re-reads the row as it was
 * <em>before</em> the uncommitted change, and repopulates the cache with the stale value - which
 * then survives until the TTL, long after the write everyone believes took effect. And if the
 * transaction subsequently rolls back, the eviction has still happened, so a correct cached value
 * was discarded for nothing.
 *
 * <p>This is the same discipline {@code IdentityProjectionService} applies for the authority cache,
 * for the same reasons, and it is stated here rather than inlined at each call site because there
 * are now four of them: a user write, an administrative write, a projected setting change and a
 * projected consent change.
 *
 * <p>When there is no active transaction - a test, a direct call outside one - the eviction happens
 * immediately. That is correct rather than a fallback: with no transaction there is nothing to wait
 * for and nothing that could roll back.
 */
public final class AfterCommitEviction {

    private AfterCommitEviction() {
    }

    /** Evicts one subject in one tenant once the current transaction commits. */
    public static void evict(SettingsCache cache, SettingsSubject subject) {
        afterCommit(() -> cache.evict(subject));
    }

    /**
     * Evicts everything once the current transaction commits.
     *
     * <p>Used after a write at a role, tenant or platform scope. Who is affected by such a write is
     * "every subject holding that role", which is a question the writer cannot answer without
     * querying the directory - so it clears the cache instead. That is a blunt instrument and the
     * right one: these writes are rare and administrative, and the alternative is either a query per
     * write or a stale value for everyone who inherits it.
     */
    public static void evictAll(SettingsCache cache) {
        afterCommit(cache::evictAll);
    }

    private static void afterCommit(Runnable eviction) {
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
