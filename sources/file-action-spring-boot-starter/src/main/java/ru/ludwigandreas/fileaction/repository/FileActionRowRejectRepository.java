package ru.ludwigandreas.fileaction.repository;

import java.util.UUID;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.fileaction.entity.FileActionRowRejectEntity;

/** Spring Data access to the bounded reject sample. */
public interface FileActionRowRejectRepository
        extends BaseRepository<FileActionRowRejectEntity, UUID>, FileActionRowRejectQueryRepository {
}
