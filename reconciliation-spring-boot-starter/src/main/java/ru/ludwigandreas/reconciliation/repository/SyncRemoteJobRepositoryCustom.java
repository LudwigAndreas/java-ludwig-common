package ru.ludwigandreas.reconciliation.repository;

import ru.ludwigandreas.reconciliation.entity.RemoteJobState;
import ru.ludwigandreas.reconciliation.entity.SyncRemoteJob;

import java.time.Instant;
import java.util.List;

/** Claims over the remote-job table, built from {@code job-core}'s shared SKIP LOCKED helper. */
public interface SyncRemoteJobRepositoryCustom {

    /**
     * Claims in-flight jobs of a task whose next probe is due, taking ownership of each.
     *
     * <p>Ownership is rewritten on every claim rather than being fixed at submit time, which is what
     * makes adoption work: a job whose original owner has been replaced is simply claimed by whoever
     * polls next, and the remote job keeps running under new management instead of being resubmitted.
     *
     * @param taskName the task
     * @param limit    maximum jobs to claim
     * @param now      the instant due-ness is evaluated against
     * @param owner    identity written into {@code owner_instance}
     * @return the claimed jobs
     */
    List<SyncRemoteJob> claimForPoll(String taskName, int limit, Instant now, String owner);

    /**
     * Claims jobs of a task whose results are ready to collect.
     *
     * @param taskName the task
     * @param limit    maximum jobs to claim
     * @param owner    identity written into {@code owner_instance}
     * @return the claimed jobs, moved to {@link RemoteJobState#COLLECTING}
     */
    List<SyncRemoteJob> claimForCollect(String taskName, int limit, String owner);
}
