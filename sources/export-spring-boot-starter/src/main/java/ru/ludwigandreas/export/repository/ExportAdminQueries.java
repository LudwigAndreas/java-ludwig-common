package ru.ludwigandreas.export.repository;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Predicate;
import java.time.Instant;
import java.util.UUID;
import ru.ludwigandreas.export.entity.QExportReportRun;
import ru.ludwigandreas.export.entity.QExportReportSubscription;

/**
 * The predicates the administrative listings read with, over the generated Q-types.
 */
public final class ExportAdminQueries {

    private static final QExportReportRun RUN = QExportReportRun.exportReportRun;
    private static final QExportReportSubscription SUBSCRIPTION = QExportReportSubscription.exportReportSubscription;

    private ExportAdminQueries() {
    }

    /**
     * The administrative runs listing: the caller's parsed {@code $filter}, after a keyset position.
     *
     * @param filter         the parsed filter, or null for every run
     * @param afterCreatedAt the last returned run's creation time, or null for the first page
     * @param afterId        the last returned run's id, or null for the first page
     * @return the predicate
     */
    public static Predicate runs(Predicate filter, Instant afterCreatedAt, UUID afterId) {
        BooleanBuilder where = new BooleanBuilder(filter);
        if (afterCreatedAt != null && afterId != null) {
            where.and(RUN.createdAt.lt(afterCreatedAt).or(RUN.createdAt.eq(afterCreatedAt).and(RUN.id.lt(afterId))));
        }
        return where;
    }

    /**
     * Subscriptions matching the filter; every member is optional.
     *
     * @param owner       who created it
     * @param savedReport which saved report it runs
     * @return the predicate
     */
    public static Predicate subscriptions(String owner, UUID savedReport) {
        BooleanBuilder where = new BooleanBuilder();
        if (owner != null && !owner.isBlank()) {
            where.and(SUBSCRIPTION.createdBy.eq(owner));
        }
        if (savedReport != null) {
            where.and(SUBSCRIPTION.savedReportId.eq(savedReport));
        }
        return where;
    }
}
