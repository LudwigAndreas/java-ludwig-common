package ru.ludwigandreas.export.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.export.api.RunStatus;
import ru.ludwigandreas.export.entity.ExportReportRun;

/**
 * Report runs.
 *
 * <p>Every method here is either derived from its own name or a QueryDSL predicate on the inherited
 * {@code QuerydslPredicateExecutor} - there is no {@code @Query} in this module, and the one native
 * statement it needs goes through {@code job-core}'s claim helper in
 * {@link ExportReportRunRepositoryCustom}.
 */
public interface ExportReportRunRepository
        extends BaseRepository<ExportReportRun, UUID>, ExportReportRunRepositoryCustom {

    /** The run a caller's idempotency key already produced, if any. */
    Optional<ExportReportRun> findByIdempotencyKey(String idempotencyKey);

    /** How many runs this requester has in flight, for the concurrent quota. */
    long countByRequesterAndStatusIn(String requester, List<RunStatus> statuses);

    /** How many runs this requester has started since an instant, for the daily quota. */
    long countByRequesterAndCreatedAtAfter(String requester, Instant since);

    /** This requester's runs, newest first. */
    Page<ExportReportRun> findByRequesterOrderByCreatedAtDesc(String requester, Pageable pageable);

    /** Runs finished before an instant, for the retention policy that removes the records too. */
    List<ExportReportRun> findByFinishedAtBefore(Instant before, Pageable pageable);
}
