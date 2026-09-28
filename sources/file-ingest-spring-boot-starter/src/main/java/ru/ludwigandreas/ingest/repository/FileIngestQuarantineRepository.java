package ru.ludwigandreas.ingest.repository;

import java.util.UUID;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.ingest.entity.FileIngestQuarantine;

/**
 * The quarantine table.
 *
 * <p>Empty for the same reason as {@link FileIngestRunRepository}: the queries are QueryDSL
 * predicates, built where they are used rather than declared as derived method names the compiler
 * cannot check.
 */
public interface FileIngestQuarantineRepository extends BaseRepository<FileIngestQuarantine, UUID> {
}
