package ru.ludwigandreas.job.core.admin.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import ru.ludwigandreas.job.core.admin.service.JobAdminViews.JobView;
import ru.ludwigandreas.job.core.admin.service.JobAdminViews.LeaseView;
import ru.ludwigandreas.job.core.lock.RunLockAdministration.RunLease;
import ru.ludwigandreas.job.core.lock.RunLockAdministration;
import ru.ludwigandreas.job.core.schedule.SelfSchedulingLifecycle;
import ru.ludwigandreas.security.admin.AdminAudit;
import ru.ludwigandreas.security.admin.AdminOutcome;

/**
 * The {@code jobs} area's operations.
 *
 * <p>Whether a job is running is answered from the lease table only, never from this replica's memory: an
 * administrative request lands on one replica, and "not running here" says nothing about the other replicas.
 */
public class JobAdminService {

    private static final String JOB = "job";
    private static final String LEASE = "run-lease";

    private final Map<String, SelfSchedulingLifecycle> jobs;
    private final RunLockAdministration leases;
    private final AdminAudit audit;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param jobs   the scheduled jobs registered in this service, by name
     * @param leases the lease table
     * @param audit  the administrative audit
     * @param clock  the clock "held now" is judged against
     */
    public JobAdminService(Map<String, SelfSchedulingLifecycle> jobs, RunLockAdministration leases, AdminAudit audit,
                           Clock clock) {
        this.jobs = Map.copyOf(jobs);
        this.leases = leases;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Every registered job, with the lease of the same name when there is one.
     *
     * @return the jobs, ordered by name
     */
    public List<JobView> jobs() {
        Instant now = clock.instant();
        Map<String, RunLease> byName = leaseMap();
        return jobs.values().stream()
                .sorted(Comparator.comparing(SelfSchedulingLifecycle::jobName))
                .map(job -> JobView.of(job.jobName(), job.schedule(),
                        Optional.ofNullable(byName.get(job.jobName())).map(lease -> LeaseView.of(lease, now))
                                .orElse(null)))
                .toList();
    }

    /**
     * Every lease row, including leases taken by components that are not scheduled jobs.
     *
     * @return the leases, ordered by name
     */
    public List<LeaseView> leases() {
        Instant now = clock.instant();
        return leases.leases().stream().map(lease -> LeaseView.of(lease, now)).toList();
    }

    /**
     * Dispatches a run of the job on this replica, unless its lease is held anywhere.
     *
     * @param name the job's name
     * @return the job as it stands
     */
    public JobView runSoon(String name) {
        SelfSchedulingLifecycle job = jobs.get(name);
        if (job == null) {
            throw JobAdminException.jobNotFound(name);
        }
        Optional<RunLease> lease = leases.lease(name);
        if (lease.isPresent() && lease.get().heldAt(clock.instant())) {
            audit.record(JOB, name, AdminOutcome.REFUSED_BY_STATE, Map.of("owner", lease.get().owner()));
            throw JobAdminException.leaseHeld(name, lease.get().owner());
        }
        if (!job.runSoon()) {
            audit.record(JOB, name, AdminOutcome.REFUSED_BY_STATE);
            throw JobAdminException.shuttingDown(name);
        }
        audit.record(JOB, name, AdminOutcome.SUCCEEDED);
        return JobView.of(name, job.schedule(), lease.map(found -> LeaseView.of(found, clock.instant())).orElse(null));
    }

    /**
     * Frees a held lease.
     *
     * @param name the lease's name
     * @return the lease as it was before the release
     */
    public LeaseView forceRelease(String name) {
        if (leases.lease(name).isEmpty()) {
            throw JobAdminException.leaseNotFound(name);
        }
        Optional<RunLease> previous = leases.forceRelease(name);
        if (previous.isEmpty()) {
            audit.record(LEASE, name, AdminOutcome.REFUSED_BY_STATE);
            throw JobAdminException.leaseNotHeld(name);
        }
        audit.record(LEASE, name, AdminOutcome.SUCCEEDED, Map.of("formerOwner", previous.get().owner()));
        return LeaseView.of(previous.get(), clock.instant());
    }

    private Map<String, RunLease> leaseMap() {
        return leases.leases().stream().collect(Collectors.toMap(RunLease::lockName, lease -> lease));
    }
}
