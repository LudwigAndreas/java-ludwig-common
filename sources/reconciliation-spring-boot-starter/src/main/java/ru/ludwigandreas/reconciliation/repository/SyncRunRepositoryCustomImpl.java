package ru.ludwigandreas.reconciliation.repository;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Predicate;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.reconciliation.entity.QSyncRun;
import ru.ludwigandreas.reconciliation.entity.SyncRun;
import ru.ludwigandreas.reconciliation.metrics.RunOutcome;

/** QueryDSL over {@code sync_run}. */
public class SyncRunRepositoryCustomImpl implements SyncRunRepositoryCustom {

    private static final QSyncRun RUN = QSyncRun.syncRun;

    @PersistenceContext
    private EntityManager entityManager;

    private JPAQueryFactory query() {
        return new JPAQueryFactory(entityManager);
    }

    @Override
    public List<SyncRun> findPage(String taskName, Predicate filter, Instant afterSubmittedAt, UUID afterId,
                                  int limit) {
        BooleanBuilder where = new BooleanBuilder(RUN.taskName.eq(taskName));
        if (filter != null) {
            where.and(filter);
        }
        if (afterSubmittedAt != null && afterId != null) {
            where.and(RUN.submittedAt.lt(afterSubmittedAt)
                    .or(RUN.submittedAt.eq(afterSubmittedAt).and(RUN.id.lt(afterId))));
        }
        return query().selectFrom(RUN).where(where)
                .orderBy(RUN.submittedAt.desc(), RUN.id.desc())
                .limit(limit)
                .fetch();
    }

    @Override
    @Transactional
    public long purgeFinishedBefore(Instant cutoff, int limit) {
        // Two statements rather than one: JPQL has no LIMIT on a DELETE or in its subquery. The purge runs under a
        // run lock, so nothing else deletes these rows between the two.
        List<UUID> expired = query().select(RUN.id).from(RUN)
                .where(RUN.finishedAt.lt(cutoff))
                .orderBy(RUN.finishedAt.asc())
                .limit(limit)
                .fetch();
        if (expired.isEmpty()) {
            return 0;
        }
        return query().delete(RUN).where(RUN.id.in(expired)).execute();
    }

    @Override
    @Transactional
    public long abandonUnfinishedBefore(String taskName, Instant cutoff, String failureCode, Instant now) {
        return query().update(RUN)
                .set(RUN.finishedAt, now)
                .set(RUN.outcome, RunOutcome.FAILURE)
                .set(RUN.failureCode, failureCode)
                .where(RUN.taskName.eq(taskName), RUN.finishedAt.isNull(), RUN.submittedAt.lt(cutoff))
                .execute();
    }
}
