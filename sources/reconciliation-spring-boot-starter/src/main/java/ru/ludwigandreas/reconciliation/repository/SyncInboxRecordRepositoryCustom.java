package ru.ludwigandreas.reconciliation.repository;

import ru.ludwigandreas.reconciliation.entity.SyncInboxRecord;

import java.time.Instant;
import java.util.List;

/**
 * The apply pass's claim, built from {@code job-core}'s shared {@code FOR UPDATE SKIP LOCKED} helper
 * rather than written out as a {@code @Query} string.
 */
public interface SyncInboxRecordRepositoryCustom {

    /**
     * Claims up to {@code batchSize} records of {@code taskName} that are due to be applied.
     *
     * <p>Claimable statuses are {@code STAGED} (never tried), {@code FAILED} (tried and failed) and
     * {@code DEFERRED} (the reconciler asked to be called back). Order is oldest-received first, tied
     * by id, so a backlog drains from its head and cannot starve its own oldest entries.
     *
     * @param taskName  the task
     * @param batchSize maximum records to claim
     * @param now       the instant due-ness is evaluated against
     * @param owner     identity written into {@code locked_by}
     * @return the claimed records
     */
    List<SyncInboxRecord> claimForApply(String taskName, int batchSize, Instant now, String owner);
}
