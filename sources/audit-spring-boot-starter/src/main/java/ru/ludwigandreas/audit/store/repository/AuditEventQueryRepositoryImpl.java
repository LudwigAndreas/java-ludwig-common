package ru.ludwigandreas.audit.store.repository;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Predicate;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.audit.store.entity.AuditEventEntity;
import ru.ludwigandreas.audit.store.entity.QAuditEventEntity;

/**
 * The trail's read side, in QueryDSL against the generated Q-type.
 *
 * <p>No JPQL and no SQL strings, like everything else in this repository outside
 * {@code ru.ludwigandreas.ingest.bulk}: a renamed or retyped column here should break the compile
 * rather than a request during an audit.
 */
class AuditEventQueryRepositoryImpl implements AuditEventQueryRepository {

    private static final QAuditEventEntity EVENT = QAuditEventEntity.auditEventEntity;

    private final JPAQueryFactory queryFactory;
    private final EntityManager entityManager;

    /**
     * Builds its own {@link JPAQueryFactory} over the injected entity manager rather than taking one from
     * the context.
     *
     * <p>Not a style choice. A {@code JPAQueryFactory} bean guarded by {@code @ConditionalOnMissingBean} is
     * not safe in a library: two autoconfigurations with no ordering between them each see no factory and
     * each register one, and anything injecting {@code JPAQueryFactory} by type then fails to start on an
     * ambiguity neither module caused alone. {@code user-settings-spring-boot-starter} publishes one, so this
     * module contributing a second was exactly that setup - the same defect this module already hit with
     * {@code Clock}. Owning a factory privately removes the bean, and with it the ordering dependency.
     *
     * <p>The injected {@code EntityManager} is Spring Data's shared, transaction-aware proxy, so one factory
     * per repository singleton is correct - it delegates to whatever persistence context the calling thread
     * is in.
     *
     * @param entityManager the shared entity manager
     */
    AuditEventQueryRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public List<AuditEventEntity> find(AuditTrailQuery query) {
        return queryFactory.selectFrom(EVENT)
                .where(predicateFor(query))
                .orderBy(EVENT.occurredAt.desc(), EVENT.id.asc())
                .limit(query.limit())
                .fetch();
    }

    @Override
    public long countOlderThan(String category, Instant cutoff) {
        Long count = queryFactory.select(EVENT.count())
                .from(EVENT)
                .where(olderThan(category, cutoff))
                .fetchOne();
        return count == null ? 0L : count;
    }

    /**
     * Two statements - select the ids, then delete by id - rather than one delete with a limit.
     *
     * <p>JPQL has no {@code LIMIT} on a delete, and selecting the ids first also means the delete
     * touches exactly the rows that were chosen, which a second evaluation of the same predicate would
     * not guarantee on a table still being written to. Inherited from
     * {@code UserSettingAuditQueryRepositoryImpl}, whose purge this one replaces.
     *
     * <p>{@code @Transactional} here and not on the job that calls it. A JPA bulk delete needs a
     * transaction, and putting the annotation on the caller made it a self-invoked method on the job -
     * {@code runOnce} to {@code purgeCategory} to {@code purgeBatch}, all inside one object, so the
     * proxy was never crossed and the annotation did nothing. The purge then failed with
     * {@code TransactionRequiredException} on every run, which a test that drove the repository directly
     * is what found. The statement that needs the transaction is the one that declares it.
     */
    @Override
    @Transactional
    public int purgeOlderThan(String category, Instant cutoff, int batchSize) {
        return purgeMatching(olderThan(category, cutoff), batchSize);
    }

    /** The two-statement delete both purge entry points share. */
    private int purgeMatching(Predicate predicate, int batchSize) {
        List<UUID> ids = queryFactory.select(EVENT.id)
                .from(EVENT)
                .where(predicate)
                .orderBy(EVENT.occurredAt.asc())
                .limit(batchSize)
                .fetch();
        if (ids.isEmpty()) {
            return 0;
        }
        long removed = queryFactory.delete(EVENT).where(EVENT.id.in(ids)).execute();
        // The rows are gone from the database and stale in the persistence context; a later read in
        // the same transaction would otherwise be served from it.
        entityManager.clear();
        return (int) removed;
    }

    @Override
    public long countOlderThanExcluding(Collection<String> excludedCategories, Instant cutoff) {
        Long count = queryFactory.select(EVENT.count())
                .from(EVENT)
                .where(olderThanExcluding(excludedCategories, cutoff))
                .fetchOne();
        return count == null ? 0L : count;
    }

    @Override
    @Transactional
    public int purgeOlderThanExcluding(Collection<String> excludedCategories, Instant cutoff, int batchSize) {
        return purgeMatching(olderThanExcluding(excludedCategories, cutoff), batchSize);
    }

    private Predicate olderThanExcluding(Collection<String> excludedCategories, Instant cutoff) {
        BooleanBuilder predicate = new BooleanBuilder(EVENT.occurredAt.lt(cutoff));
        if (excludedCategories != null && !excludedCategories.isEmpty()) {
            predicate.and(EVENT.category.notIn(excludedCategories));
        }
        return predicate;
    }

    private Predicate olderThan(String category, Instant cutoff) {
        BooleanBuilder predicate = new BooleanBuilder(EVENT.occurredAt.lt(cutoff));
        if (category != null && !category.isBlank()) {
            predicate.and(EVENT.category.eq(category));
        }
        return predicate;
    }

    /**
     * The criteria as one predicate.
     *
     * <p>A {@link BooleanBuilder} rather than a chain of ternaries: every clause is independently
     * optional, and an absent clause must contribute nothing rather than {@code true} - which is the
     * bug a hand-rolled {@code and} chain with nulls in it produces.
     */
    private Predicate predicateFor(AuditTrailQuery query) {
        BooleanBuilder predicate = new BooleanBuilder();
        if (query.actorSubject() != null) {
            predicate.and(EVENT.actorSubject.eq(query.actorSubject()));
        }
        if (query.onBehalfOf() != null) {
            predicate.and(EVENT.onBehalfOf.eq(query.onBehalfOf()));
        }
        if (!query.categories().isEmpty()) {
            predicate.and(EVENT.category.in(query.categories()));
        }
        if (!query.actions().isEmpty()) {
            predicate.and(EVENT.action.in(query.actions()));
        }
        if (query.resourceType() != null) {
            predicate.and(EVENT.resourceType.eq(query.resourceType()));
        }
        if (query.resourceId() != null) {
            predicate.and(EVENT.resourceId.eq(query.resourceId()));
        }
        if (!query.outcomes().isEmpty()) {
            predicate.and(EVENT.outcome.in(query.outcomes()));
        }
        if (query.correlationId() != null) {
            predicate.and(EVENT.correlationId.eq(query.correlationId()));
        }
        if (query.from() != null) {
            predicate.and(EVENT.occurredAt.goe(query.from()));
        }
        if (query.to() != null) {
            predicate.and(EVENT.occurredAt.lt(query.to()));
        }
        return predicate;
    }
}
