package ru.ludwigandreas.audit.store.repository;

import java.util.UUID;
import ru.ludwigandreas.audit.store.entity.AuditEventEntity;
import ru.ludwigandreas.db.core.repository.BaseRepository;

/** The trail. */
public interface AuditEventRepository
        extends BaseRepository<AuditEventEntity, UUID>, AuditEventQueryRepository {
}
