package ru.ludwigandreas.notification.repository;

import java.util.UUID;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.notification.repository.entity.RecipientProfileEntity;

/** Contact records; its queries live in {@link RecipientProfileQueryRepository}. */
public interface RecipientProfileRepository
        extends BaseRepository<RecipientProfileEntity, UUID>, RecipientProfileQueryRepository {
}
