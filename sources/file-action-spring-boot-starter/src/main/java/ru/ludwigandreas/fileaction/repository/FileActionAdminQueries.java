package ru.ludwigandreas.fileaction.repository;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Predicate;
import java.time.Instant;
import java.util.UUID;
import ru.ludwigandreas.fileaction.entity.QFileActionSubmissionEntity;

/**
 * The predicate the administrative submission listing reads with, over the generated Q-type.
 */
public final class FileActionAdminQueries {

    private static final QFileActionSubmissionEntity SUBMISSION =
            QFileActionSubmissionEntity.fileActionSubmissionEntity;

    private FileActionAdminQueries() {
    }

    /**
     * The administrative submissions listing: the caller's parsed {@code $filter}, after a keyset position.
     *
     * @param filter           the parsed filter, or null for every submission
     * @param afterSubmittedAt the last returned submission's time
     * @param afterId          the last returned submission's id
     * @return the predicate
     */
    public static Predicate submissions(Predicate filter, Instant afterSubmittedAt, UUID afterId) {
        BooleanBuilder where = new BooleanBuilder(filter);
        if (afterSubmittedAt != null && afterId != null) {
            where.and(SUBMISSION.submittedAt.lt(afterSubmittedAt)
                    .or(SUBMISSION.submittedAt.eq(afterSubmittedAt).and(SUBMISSION.id.lt(afterId))));
        }
        return where;
    }
}
