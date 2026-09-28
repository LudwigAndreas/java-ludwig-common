package ru.ludwigandreas.notification.repository;

import java.util.UUID;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.notification.repository.entity.SuppressionEntity;

/** The suppression list; its queries live in {@link SuppressionQueryRepository}. */
public interface SuppressionRepository
        extends BaseRepository<SuppressionEntity, UUID>, SuppressionQueryRepository {
}
