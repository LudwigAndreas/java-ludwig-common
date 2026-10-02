package ru.ludwigandreas.fileaction.repository;

import java.util.UUID;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.fileaction.entity.FileActionSubmissionEntity;

/**
 * Spring Data access to submissions.
 *
 * <p>Deliberately carries no derived query methods. Every read with a condition on it is a QueryDSL
 * expression in {@link FileActionSubmissionQueryRepository}, which is the platform's data-access rule: a
 * derived method name is a query the compiler does not check and a refactoring tool cannot follow.
 */
public interface FileActionSubmissionRepository
        extends BaseRepository<FileActionSubmissionEntity, UUID>, FileActionSubmissionQueryRepository {
}
