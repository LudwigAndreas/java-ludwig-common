package ru.ludwigandreas.reconciliation.repository;

import java.util.UUID;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.reconciliation.entity.SyncRun;

/** Run history. */
public interface SyncRunRepository extends BaseRepository<SyncRun, UUID>, SyncRunRepositoryCustom {
}
