package ru.ludwigandreas.export.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.export.entity.ExportReportOutput;

/** The files runs produced. */
public interface ExportReportOutputRepository extends BaseRepository<ExportReportOutput, UUID> {

    /** Every output of a run, for a status response and for deleting them all at once. */
    List<ExportReportOutput> findByRunId(UUID runId);

    /** One run's output in one format, which is what a download addresses. */
    Optional<ExportReportOutput> findByRunIdAndFormatId(UUID runId, String formatId);

    /**
     * Outputs past their expiry whose bytes are still there.
     *
     * <p>Paged rather than returning everything: a purge that has been switched off for a month has a
     * very large backlog, and loading it in one list is the one place this module could plausibly
     * exhaust its own heap on metadata rather than on report rows.
     */
    List<ExportReportOutput> findByPurgedAtIsNullAndExpiresAtBefore(Instant before, Pageable pageable);
}
