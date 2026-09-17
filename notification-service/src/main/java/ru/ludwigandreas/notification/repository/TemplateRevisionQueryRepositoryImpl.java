package ru.ludwigandreas.notification.repository;

import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import ru.ludwigandreas.notification.repository.entity.QTemplateRevisionEntity;
import ru.ludwigandreas.notification.repository.entity.TemplateRevisionEntity;

/** QueryDSL implementation of {@link TemplateRevisionQueryRepository}. */
@RequiredArgsConstructor
class TemplateRevisionQueryRepositoryImpl implements TemplateRevisionQueryRepository {

    private static final QTemplateRevisionEntity REVISION = QTemplateRevisionEntity.templateRevisionEntity;

    private final JPAQueryFactory queryFactory;

    @Override
    public Optional<TemplateRevisionEntity> findByNameAndHash(String templateName, String contentHash) {
        return Optional.ofNullable(queryFactory.selectFrom(REVISION)
                .where(REVISION.templateName.eq(templateName).and(REVISION.contentHash.eq(contentHash)))
                .fetchFirst());
    }

    @Override
    public int highestRevision(String templateName) {
        Integer highest = queryFactory.select(REVISION.revision.max())
                .from(REVISION)
                .where(REVISION.templateName.eq(templateName))
                .fetchOne();
        return highest == null ? 0 : highest;
    }

    @Override
    public List<TemplateRevisionEntity> listRevisions(String templateName) {
        return queryFactory.selectFrom(REVISION)
                .where(REVISION.templateName.eq(templateName))
                .orderBy(REVISION.revision.desc())
                .fetch();
    }
}
