package ru.ludwigandreas.usersettings.repository;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import ru.ludwigandreas.usersettings.api.SettingLayer;
import ru.ludwigandreas.usersettings.api.SettingScope;
import ru.ludwigandreas.usersettings.entity.QUserSettingValueEntity;
import ru.ludwigandreas.usersettings.entity.UserSettingValueEntity;

@RequiredArgsConstructor
class UserSettingValueQueryRepositoryImpl implements UserSettingValueQueryRepository {

    private static final QUserSettingValueEntity VALUE = QUserSettingValueEntity.userSettingValueEntity;

    private final JPAQueryFactory queryFactory;
    private final EntityManager entityManager;

    /**
     * Scopes are grouped by layer so the predicate becomes one {@code IN} per layer rather than one
     * equality pair per scope. A user in thirty roles then produces a predicate with three branches
     * instead of thirty-two, and the composite index on {@code (tenant_id, scope_type, scope_id)}
     * serves each branch directly.
     */
    @Override
    public List<UserSettingValueEntity> loadForScopes(String tenantId, List<SettingScope> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return List.of();
        }
        Map<SettingLayer, Set<String>> idsByLayer = new LinkedHashMap<>();
        for (SettingScope scope : scopes) {
            if (isStored(scope.layer())) {
                idsByLayer.computeIfAbsent(scope.layer(), layer -> new LinkedHashSet<>()).add(scope.scopeId());
            }
        }
        if (idsByLayer.isEmpty()) {
            return List.of();
        }

        BooleanBuilder scopePredicate = new BooleanBuilder();
        idsByLayer.forEach((layer, ids) ->
                scopePredicate.or(VALUE.scopeType.eq(layer).and(VALUE.scopeId.in(ids))));

        // Tombstones are excluded here, not filtered by the caller: a removed value must resolve as
        // "nothing at this layer" so the layer below supplies one, and a caller that forgot the
        // filter would see the tombstone win and the setting resolve to null.
        return queryFactory.selectFrom(VALUE)
                .where(VALUE.tenantId.eq(tenantId).and(scopePredicate).and(VALUE.removed.isFalse()))
                .fetch();
    }

    @Override
    public Optional<UserSettingValueEntity> findValue(String tenantId, SettingScope scope, String settingKey) {
        return Optional.ofNullable(queryFactory.selectFrom(VALUE)
                .where(scopeEquals(tenantId, scope).and(VALUE.settingKey.eq(settingKey)))
                .fetchFirst());
    }

    @Override
    public List<UserSettingValueEntity> findByScope(String tenantId, SettingScope scope) {
        return queryFactory.selectFrom(VALUE)
                .where(scopeEquals(tenantId, scope).and(VALUE.removed.isFalse()))
                .orderBy(VALUE.settingKey.asc())
                .fetch();
    }

    @Override
    public List<UserSettingValueEntity> findForBackfill(String tenantId, Collection<String> settingKeys,
                                                        UUID afterId, int limit) {
        BooleanBuilder predicate = new BooleanBuilder();
        if (tenantId != null) {
            predicate.and(VALUE.tenantId.eq(tenantId));
        }
        if (settingKeys != null && !settingKeys.isEmpty()) {
            predicate.and(VALUE.settingKey.in(settingKeys));
        }
        if (afterId != null) {
            predicate.and(VALUE.id.gt(afterId));
        }
        return queryFactory.selectFrom(VALUE)
                .where(predicate)
                .orderBy(VALUE.id.asc())
                .limit(limit)
                .fetch();
    }

    /**
     * Two statements - select the ids, then delete by id - because JPQL has no {@code LIMIT} on a
     * delete. A bulk delete also bypasses the persistence context, so it is cleared afterwards to
     * keep the in-memory state and the database agreeing; this runs from a scheduled purge with
     * nothing else in its transaction, so clearing costs nothing.
     */
    @Override
    public int purgeTombstonesOlderThan(Instant cutoff, int batchSize) {
        List<UUID> ids = queryFactory.select(VALUE.id)
                .from(VALUE)
                .where(VALUE.removed.isTrue().and(VALUE.changedAt.lt(cutoff)))
                .orderBy(VALUE.changedAt.asc())
                .limit(batchSize)
                .fetch();
        if (ids.isEmpty()) {
            return 0;
        }
        long removed = queryFactory.delete(VALUE).where(VALUE.id.in(ids)).execute();
        entityManager.clear();
        return (int) removed;
    }

    private static BooleanExpression scopeEquals(String tenantId, SettingScope scope) {
        return VALUE.tenantId.eq(tenantId)
                .and(VALUE.scopeType.eq(scope.layer()))
                .and(VALUE.scopeId.eq(scope.scopeId()));
    }

    /** {@link SettingLayer#PLATFORM} and {@link SettingLayer#DEFAULT} are never rows; see the entity. */
    private static boolean isStored(SettingLayer layer) {
        return layer == SettingLayer.USER || layer == SettingLayer.ROLE || layer == SettingLayer.TENANT;
    }
}
