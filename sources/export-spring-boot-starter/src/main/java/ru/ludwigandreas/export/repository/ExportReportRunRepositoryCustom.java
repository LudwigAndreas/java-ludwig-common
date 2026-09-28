package ru.ludwigandreas.export.repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ru.ludwigandreas.export.entity.ExportReportRun;

/**
 * The two things a report run needs that Spring Data cannot derive: claiming and reclaiming.
 *
 * <p>Both are single statements that read and write at once, which is the only way either of them is
 * correct under concurrency. Expressing them as a read followed by a write - find due rows, then mark
 * them - is the classic way two instances claim the same run and produce the same report twice.
 */
public interface ExportReportRunRepositoryCustom {

    /**
     * Claims runs that are due, marking them RUNNING and taking the lease in one statement.
     *
     * @param owner    this instance's identity, written into {@code claimed_by}
     * @param now      the current instant, from the injected clock
     * @param lease    how long the claim is good for without a heartbeat
     * @param limit    the most runs to claim in this call
     * @return the claimed runs, in claim order
     */
    List<ExportReportRun> claimDue(String owner, Instant now, Duration lease, int limit);

    /**
     * Returns runs whose lease has expired to PENDING so another instance can take them.
     *
     * <p>This is what makes a run survive the death of the instance executing it. The instance stopped
     * renewing; after the lease elapses the row is no longer being worked on by anybody, whatever its
     * status says, and pretending otherwise leaves it in RUNNING until a human notices.
     *
     * <p>A reclaimed run restarts from scratch and counts as an attempt, so a run that repeatedly
     * kills the instance executing it exhausts its budget and fails rather than looping forever.
     *
     * @param now   the current instant
     * @param limit the most runs to reclaim in this call
     * @return how many rows were returned to PENDING
     */
    int reclaimExpired(Instant now, int limit);

    /**
     * Renews the lease on a run this instance is executing.
     *
     * @param runId the run
     * @param owner this instance's identity; a renewal by anybody else is refused
     * @param now   the current instant
     * @param lease how much longer the lease is good for
     * @return false when the lease is no longer this instance's, which means another instance has
     *         taken the run and this one must stop immediately rather than finish the window
     */
    boolean renewLease(UUID runId, String owner, Instant now, Duration lease);
}
