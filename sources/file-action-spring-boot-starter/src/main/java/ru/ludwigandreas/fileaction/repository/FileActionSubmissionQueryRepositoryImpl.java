package ru.ludwigandreas.fileaction.repository;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.querydsl.jpa.impl.JPAUpdateClause;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ru.ludwigandreas.fileaction.api.FileActionState;
import ru.ludwigandreas.fileaction.entity.FileActionSubmissionEntity;
import ru.ludwigandreas.fileaction.entity.QFileActionSubmissionEntity;

/** QueryDSL implementation of {@link FileActionSubmissionQueryRepository}. */
class FileActionSubmissionQueryRepositoryImpl implements FileActionSubmissionQueryRepository {

    private static final QFileActionSubmissionEntity SUBMISSION =
            QFileActionSubmissionEntity.fileActionSubmissionEntity;

    private final JPAQueryFactory queryFactory;

    /**
     * Builds its own {@link JPAQueryFactory} over the injected entity manager rather than taking one from the
     * context.
     *
     * <p>The reason is the one {@code idempotency} and {@code audit} record: a {@code JPAQueryFactory} bean
     * guarded by {@code @ConditionalOnMissingBean} is not safe in a library, because two autoconfigurations
     * with no ordering between them each see no factory and each register one, and anything injecting it by
     * type then fails to start on an ambiguity neither module caused alone.
     *
     * <p>The injected {@code EntityManager} is Spring Data's transaction-aware proxy, so one factory per
     * repository singleton is correct: it delegates to whatever persistence context the calling thread is in,
     * which here is a request thread and the deferred worker's thread at the same time.
     *
     * @param entityManager the shared entity manager
     */
    FileActionSubmissionQueryRepositoryImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public Optional<FileActionSubmissionEntity> findByActionAndId(String action, UUID id) {
        return Optional.ofNullable(queryFactory.selectFrom(SUBMISSION)
                .where(SUBMISSION.action.eq(action), SUBMISSION.id.eq(id))
                .fetchFirst());
    }

    @Override
    public Optional<FileActionSubmissionEntity> findByContent(String action, String sha256,
                                                              String submitter) {
        return Optional.ofNullable(queryFactory.selectFrom(SUBMISSION)
                .where(SUBMISSION.action.eq(action),
                        SUBMISSION.contentSha256.eq(sha256),
                        submitter == null ? SUBMISSION.submittedBy.isNull()
                                : SUBMISSION.submittedBy.eq(submitter))
                .fetchFirst());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Transactional, and it has to be: the claim is an update, and the deferred worker calls this from a
     * scheduler thread with no transaction of its own - so without this the first tick in production fails with
     * "Executing an update/delete query" and no submission is ever processed. {@code DeferredClaimIT} found it.
     *
     * <p>Its own transaction rather than the caller's, deliberately: the claim must be visible to other instances
     * before the work starts, and a claim that shared the apply's transaction would only become visible when the
     * apply committed - by which time a second instance has claimed the same submission.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<FileActionSubmissionEntity> claimForProcessing(FileActionState state, String owner,
                                                               Instant now, Instant leaseUntil,
                                                               int limit) {
        // Two statements rather than job-core's single UPDATE ... RETURNING, deliberately. SkipLockedClaim
        // renders that statement for a JDBC template; this module's writes go through the ORM so that the
        // @Version column is honoured - and the version is what stops a concurrent cancel being lost. The
        // select below takes the ids, the update claims them, and a row another instance claimed in between
        // fails the state predicate rather than being claimed twice.
        List<UUID> candidates = queryFactory.select(SUBMISSION.id)
                .from(SUBMISSION)
                .where(SUBMISSION.state.eq(state),
                        SUBMISSION.leaseExpiresAt.isNull().or(SUBMISSION.leaseExpiresAt.lt(now)))
                .orderBy(SUBMISSION.submittedAt.asc())
                .limit(limit)
                .fetch();
        if (candidates.isEmpty()) {
            return List.of();
        }
        JPAUpdateClause update = queryFactory.update(SUBMISSION);
        long claimed = update
                .set(SUBMISSION.lockedBy, owner)
                .set(SUBMISSION.leaseExpiresAt, leaseUntil)
                .set(SUBMISSION.updatedAt, now)
                .where(SUBMISSION.id.in(candidates),
                        SUBMISSION.state.eq(state),
                        SUBMISSION.leaseExpiresAt.isNull().or(SUBMISSION.leaseExpiresAt.lt(now)))
                .execute();
        if (claimed == 0) {
            return List.of();
        }
        return queryFactory.selectFrom(SUBMISSION)
                .where(SUBMISSION.id.in(candidates), SUBMISSION.lockedBy.eq(owner))
                .fetch();
    }

    @Override
    public List<FileActionSubmissionEntity> findExpired(Instant now, int limit) {
        return queryFactory.selectFrom(SUBMISSION)
                .where(SUBMISSION.expiresAt.isNotNull(),
                        SUBMISSION.expiresAt.lt(now),
                        SUBMISSION.state.ne(FileActionState.EXPIRED))
                .orderBy(SUBMISSION.expiresAt.asc())
                .limit(limit)
                .fetch();
    }

    @Override
    public boolean isCancellationRequested(UUID id) {
        Boolean requested = queryFactory.select(SUBMISSION.cancellationRequested)
                .from(SUBMISSION)
                .where(SUBMISSION.id.eq(id))
                .fetchFirst();
        return Boolean.TRUE.equals(requested);
    }
}
