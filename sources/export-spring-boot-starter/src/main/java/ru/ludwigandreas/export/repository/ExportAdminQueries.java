package ru.ludwigandreas.export.repository;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Predicate;
import java.time.Instant;
import java.util.UUID;
import ru.ludwigandreas.export.entity.QExportReportRun;
import ru.ludwigandreas.export.entity.QExportReportSubscription;
import ru.ludwigandreas.webcore.operation.OperationStatus;

/**
 * The predicates the administrative listings read with, over the generated Q-types.
 */
public final class ExportAdminQueries {

    private static final QExportReportRun RUN = QExportReportRun.exportReportRun;
    private static final QExportReportSubscription SUBSCRIPTION = QExportReportSubscription.exportReportSubscription;

    private ExportAdminQueries() {
    }

    /**
     * Runs matching the filter, after a keyset position; every member is optional.
     *
     * @param requester       who asked for it
     * @param definitionKey   which report
     * @param status          its state
     * @param afterCreatedAt  the last returned run's creation time
     * @param afterId         the last returned run's id
     * @return the predicate
     */
    public static Predicate runs(String requester, String definitionKey, OperationStatus status,
                                 Instant afterCreatedAt, UUID afterId) {
        BooleanBuilder where = new BooleanBuilder();
        if (requester != null && !requester.isBlank()) {
            where.and(RUN.requester.eq(requester));
        }
        if (definitionKey != null && !definitionKey.isBlank()) {
            where.and(RUN.definitionKey.eq(definitionKey));
        }
        if (status != null) {
            where.and(RUN.status.eq(status));
        }
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
