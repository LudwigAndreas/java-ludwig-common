package ru.ludwigandreas.export.repository;

import java.util.List;
import java.util.UUID;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.export.entity.ExportReportSubscription;

/** Saved reports on a schedule. */
public interface ExportReportSubscriptionRepository
        extends BaseRepository<ExportReportSubscription, UUID> {

    /** Every live subscription, which is what the scheduler reads each tick. */
    List<ExportReportSubscription> findByEnabledTrue();

    /** The subscriptions of one saved report, so deleting it can say what depends on it. */
    List<ExportReportSubscription> findBySavedReportId(UUID savedReportId);
}
