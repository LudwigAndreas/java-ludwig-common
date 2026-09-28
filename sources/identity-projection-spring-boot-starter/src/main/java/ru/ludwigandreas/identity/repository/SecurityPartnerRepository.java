package ru.ludwigandreas.identity.repository;

import java.util.UUID;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.identity.entity.SecurityPartnerEntity;

public interface SecurityPartnerRepository
        extends BaseRepository<SecurityPartnerEntity, UUID>, SecurityPartnerQueryRepository {
}
