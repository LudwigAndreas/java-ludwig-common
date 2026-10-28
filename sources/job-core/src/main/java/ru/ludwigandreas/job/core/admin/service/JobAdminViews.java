package ru.ludwigandreas.job.core.admin.service;

import java.time.Instant;
import ru.ludwigandreas.job.core.lock.RunLockAdministration.RunLease;
import ru.ludwigandreas.job.core.schedule.ScheduleSpec;

/**
 * What the {@code jobs} area answers with.
 */
public final class JobAdminViews {

    private JobAdminViews() {
    }

    /**
     * One scheduled job.
     *
     * @param name     the job's name
     * @param schedule {@code cron <expression>} or {@code every <duration>}
     * @param lease    the run lease of the same name, or null when the job serializes some other way
     */
    public record JobView(String name, String schedule, LeaseView lease) {

        static JobView of(String name, ScheduleSpec schedule, LeaseView lease) {
            return new JobView(name, schedule.isCron() ? "cron " + schedule.cron() : "every " + schedule.fixedDelay(),
                    lease);
        }
    }

    /**
     * One run lease, as the lease table records it.
     *
     * @param name        the lease's name
     * @param owner       the instance holding it, or null
     * @param held        whether it is held now
     * @param acquiredAt  when it was last acquired
     * @param heartbeatAt when it was last renewed
     * @param expiresAt   when it lapses unless renewed
     */
    public record LeaseView(String name, String owner, boolean held, Instant acquiredAt, Instant heartbeatAt,
                            Instant expiresAt) {

        static LeaseView of(RunLease lease, Instant now) {
            return new LeaseView(lease.lockName(), lease.owner(), lease.heldAt(now), lease.acquiredAt(),
                    lease.heartbeatAt(), lease.expiresAt());
        }
    }
}
