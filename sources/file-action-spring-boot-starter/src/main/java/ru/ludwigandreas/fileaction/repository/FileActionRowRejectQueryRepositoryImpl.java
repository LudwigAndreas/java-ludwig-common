package ru.ludwigandreas.fileaction.repository;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import ru.ludwigandreas.fileaction.entity.FileActionRowRejectEntity;
import ru.ludwigandreas.fileaction.entity.QFileActionRowRejectEntity;

/** QueryDSL implementation of {@link FileActionRowRejectQueryRepository}. */
class FileActionRowRejectQueryRepositoryImpl implements FileActionRowRejectQueryRepository {

    private static final QFileActionRowRejectEntity REJECT =
            QFileActionRowRejectEntity.fileActionRowRejectEntity;

    private final JPAQueryFactory queryFactory;

    /** See {@code FileActionSubmissionQueryRepositoryImpl} for why the factory is built here. */
    FileActionRowRejectQueryRepositoryImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public Page<FileActionRowRejectEntity> findBySubmission(UUID submissionId, Pageable pageable) {
        List<FileActionRowRejectEntity> content = queryFactory.selectFrom(REJECT)
                .where(REJECT.submission.id.eq(submissionId))
                .orderBy(REJECT.displayedRow.asc(), REJECT.createdAt.asc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        return new PageImpl<>(content, pageable, countBySubmission(submissionId));
    }

    @Override
    public long countBySubmission(UUID submissionId) {
        Long count = queryFactory.select(REJECT.count())
                .from(REJECT)
                .where(REJECT.submission.id.eq(submissionId))
                .fetchFirst();
        return count == null ? 0L : count;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Joins the caller's transaction rather than opening its own: the only caller is
     * {@code SubmissionStore.replaceRejects}, which deletes and re-inserts, and the two have to be one unit or a
     * failure between them leaves a submission with no rejects at all.
     */
    @Override
    @Transactional
    public long deleteBySubmission(UUID submissionId) {
        return queryFactory.delete(REJECT)
                .where(REJECT.submission.id.eq(submissionId))
                .execute();
    }
}
