package ru.ludwigandreas.reconciliation.repository;

import org.springframework.data.domain.Pageable;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.reconciliation.entity.SyncAuditRecord;

import java.util.List;
import java.util.UUID;

/** The persisted audit trail, written only when a task's {@code audit.persist} is on. */
public interface SyncAuditRecordRepository extends BaseRepository<SyncAuditRecord, UUID> {

    /**
     * A task's audit trail, newest first.
     *
     * @param taskName the task
     * @param pageable the page
     * @return the events
     */
    List<SyncAuditRecord> findByTaskNameOrderByOccurredAtDesc(String taskName, Pageable pageable);
}
