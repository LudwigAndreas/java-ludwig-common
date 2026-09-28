package ru.ludwigandreas.identity.repository;

import java.util.UUID;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.identity.entity.SecurityGrantEntity;

public interface SecurityGrantRepository
        extends BaseRepository<SecurityGrantEntity, UUID>, SecurityGrantQueryRepository {
}
