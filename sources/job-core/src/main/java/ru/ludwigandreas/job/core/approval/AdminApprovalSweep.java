package ru.ludwigandreas.job.core.approval;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.job.core.lock.RunLockHandle;
import ru.ludwigandreas.job.core.schedule.ScheduleSpec;
import ru.ludwigandreas.job.core.schedule.SelfSchedulingLifecycle;
import ru.ludwigandreas.security.admin.AdminApprovals;

/**
 * Expires undecided approvals and marks as interrupted those a stopped replica left executing - one replica at a
 * time, under the run lock of the same name. The decisions and the audit are the security starter's
 * ({@link AdminApprovals#sweep()}); this only schedules them.
 */
public class AdminApprovalSweep extends SelfSchedulingLifecycle {

    /** The job's name, and its run lock's. */
    public static final String JOB_NAME = "ludwig-admin-approval-expiry";

    /** How often it runs. */
    static final Duration INTERVAL = Duration.ofMinutes(1);

    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(30);
    private static final Logger log = LoggerFactory.getLogger(AdminApprovalSweep.class);

    private final AdminApprovals approvals;
    private final RunLock lock;

    /**
     * Creates the sweep.
     *
     * @param scheduler the scheduler it runs on
     * @param approvals the approvals it sweeps
     * @param lock      the run lock that keeps it to one replica
     */
    public AdminApprovalSweep(TaskScheduler scheduler, AdminApprovals approvals, RunLock lock) {
        super(JOB_NAME, scheduler, ScheduleSpec.fixedDelay(INTERVAL), DRAIN_TIMEOUT);
        this.approvals = approvals;
        this.lock = lock;
    }

    @Override
    protected void runOnce() {
        lock.tryAcquire(JOB_NAME, INTERVAL.multipliedBy(2)).ifPresent(this::sweepUnder);
    }

    private void sweepUnder(RunLockHandle handle) {
        try (RunLockHandle held = handle) {
            int moved = approvals.sweep();
            if (moved > 0) {
                log.info("Approval sweep expired or interrupted {} approval(s)", moved);
            }
        } catch (RuntimeException e) {
            // CHECKSTYLE.OFF: IllegalCatch - a failed tick must not end the schedule; the next one tries again.
            log.warn("Approval sweep failed: {}", e.toString());
            // CHECKSTYLE.ON: IllegalCatch
        }
    }
}
