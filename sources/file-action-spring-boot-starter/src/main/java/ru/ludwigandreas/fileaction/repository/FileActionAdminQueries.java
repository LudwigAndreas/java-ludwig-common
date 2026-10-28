package ru.ludwigandreas.fileaction.repository;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Predicate;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import ru.ludwigandreas.fileaction.api.FileActionState;
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
     * Submissions matching the filter, after a keyset position; every member is optional.
     *
     * @param action           the action
     * @param submitter        who submitted it
     * @param states           its states, or null for any
     * @param afterSubmittedAt the last returned submission's time
     * @param afterId          the last returned submission's id
     * @return the predicate
     */
    public static Predicate submissions(String action, String submitter, Collection<FileActionState> states,
                                        Instant afterSubmittedAt, UUID afterId) {
        BooleanBuilder where = new BooleanBuilder();
        if (action != null && !action.isBlank()) {
            where.and(SUBMISSION.action.eq(action));
        }
        if (submitter != null && !submitter.isBlank()) {
            where.and(SUBMISSION.submittedBy.eq(submitter));
        }
        if (states != null) {
            where.and(SUBMISSION.state.in(states));
        }
        if (afterSubmittedAt != null && afterId != null) {
            where.and(SUBMISSION.submittedAt.lt(afterSubmittedAt)
                    .or(SUBMISSION.submittedAt.eq(afterSubmittedAt).and(SUBMISSION.id.lt(afterId))));
        }
        return where;
    }
}
