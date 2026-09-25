package ru.ludwigandreas.ingest.repository;

import java.util.UUID;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.ingest.entity.FileIngestRun;

/**
 * The run table.
 *
 * <p>No derived query methods and no JPQL: every read is a QueryDSL predicate built in
 * {@link IngestRunQueries}, which is this repository's convention and the platform's. The interface is
 * otherwise empty because {@code BaseRepository} already carries {@code QuerydslPredicateExecutor},
 * which is the only query surface this module needs.
 */
public interface FileIngestRunRepository extends BaseRepository<FileIngestRun, UUID> {
}
