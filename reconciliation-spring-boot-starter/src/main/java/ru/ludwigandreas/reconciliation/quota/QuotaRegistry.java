package ru.ludwigandreas.reconciliation.quota;

import ru.ludwigandreas.reconciliation.config.TaskSettings;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The quotas, addressed the way callers actually need them: by task rather than by name, and with the
 * "this task is not gated by a quota" case answered here instead of at every call site.
 *
 * <p>A task with no quota gets an unlimited handle rather than an empty {@link Optional}, so that the
 * submit path has one shape. Returning empty would mean every caller has to distinguish "no slot
 * available" from "no quota configured", and getting that branch wrong in either direction is either
 * a task that never runs or a task that ignores a limit.
 */
public class QuotaRegistry {

    private final DatabaseQuota quota;

    /**
     * Creates the registry.
     *
     * @param quota the database-backed quota
     */
    public QuotaRegistry(DatabaseQuota quota) {
        this.quota = quota;
    }

    /**
     * Takes a slot for a task, if it is gated by a quota.
     *
     * @param settings the task's settings
     * @param jobId    the remote job the slot is for, or null
     * @return the handle, an unlimited one for a task with no quota, or empty when the quota is full
     */
    public Optional<QuotaLeaseHandle> tryAcquire(TaskSettings settings, UUID jobId) {
        return settings.quotaName()
                .map(name -> quota.tryAcquire(name, settings.name(), jobId))
                .orElseGet(() -> Optional.of(unlimited()));
    }

    /**
     * Re-attaches to an existing lease after adopting the job it was held for.
     *
     * @param leaseId the lease recorded on the job, or null when the task has no quota
     * @return the handle, or empty if the lease has expired and been reclaimed
     */
    public Optional<QuotaLeaseHandle> adopt(UUID leaseId) {
        return leaseId == null ? Optional.of(unlimited()) : quota.adopt(leaseId);
    }

    /** The underlying quota, for the reclaim sweep and the actuator endpoint. */
    public DatabaseQuota quota() {
        return quota;
    }

    /** Every configured quota name. */
    public Set<String> names() {
        return quota.names();
    }

    /**
     * The handle a task with no quota gets: it counts nothing, renews trivially and releases nothing.
     *
     * <p>Not a null object smuggled in as a shortcut - it is the accurate model of "this partner has
     * no stated concurrency limit", and it keeps the lease lifecycle in the calling code identical in
     * both cases so there is only one path to get right.
     */
    private static QuotaLeaseHandle unlimited() {
        return new QuotaLeaseHandle() {
            @Override
            public UUID leaseId() {
                return null;
            }

            @Override
            public String quotaName() {
                return null;
            }

            @Override
            public boolean renew() {
                return true;
            }

            @Override
            public void close() {
                // Nothing was taken, so there is nothing to give back.
            }
        };
    }
}
