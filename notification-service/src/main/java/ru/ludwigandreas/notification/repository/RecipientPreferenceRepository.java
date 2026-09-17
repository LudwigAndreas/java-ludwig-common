package ru.ludwigandreas.notification.repository;

import java.util.UUID;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.notification.repository.entity.RecipientPreferenceEntity;

/** Opt-outs and explicit opt-ins; its queries live in {@link PreferenceQueryRepository}. */
public interface RecipientPreferenceRepository
        extends BaseRepository<RecipientPreferenceEntity, UUID>, PreferenceQueryRepository {
}
