package ru.ludwigandreas.pat.repository;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import ru.ludwigandreas.pat.entity.PatEntity;
import ru.ludwigandreas.pat.entity.QPatEntity;

/**
 * {@link PatQueryRepository} in QueryDSL against the generated Q-type.
 *
 * <p>No SQL, no JPQL string, no native query - and {@code SqlConfinementTest} fails the build if any appears
 * anywhere in this module. That test takes the <b>inverse</b> shape of the ones in
 * {@code file-ingest-spring-boot-starter} and {@code idempotency-spring-boot-starter}: rather than fencing SQL
 * into one package, it asserts this module has none at all.
 *
 * <p>Worth saying why, because the instinct is to reach for the idempotency carve-out. "Claim a token
 * atomically" and "claim an idempotency key atomically" read as the same problem and are not. The idempotency
 * claim <em>writes</em> in order to claim, which needs {@code INSERT ... ON CONFLICT ... RETURNING} that JPQL
 * has neither half of. Token verification only <em>reads</em>: a point read on a unique index, then a
 * constant-time digest comparison in the application. There is nothing for {@code ON CONFLICT} to do, and so
 * there is no third carve-out.
 */
public class PatQueryRepositoryImpl implements PatQueryRepository {

    private static final QPatEntity PAT = QPatEntity.patEntity;

    private final JPAQueryFactory queryFactory;

    public PatQueryRepositoryImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public Optional<PatEntity> findByAnyKeyId(String keyId) {
        if (keyId == null || keyId.isBlank()) {
            return Optional.empty();
        }
        // Either key id, because during a rotation overlap a consumer may still present the old secret and
        // it must resolve to the same token. Both columns are uniquely indexed, so this stays a point read.
        return Optional.ofNullable(queryFactory.selectFrom(PAT)
                .where(PAT.keyId.eq(keyId).or(PAT.previousKeyId.eq(keyId)))
                .fetchFirst());
    }

    @Override
    public List<PatEntity> findByOwner(String ownerSubject) {
        return queryFactory.selectFrom(PAT)
                .where(PAT.ownerSubject.eq(ownerSubject))
                .orderBy(PAT.createdAt.desc())
                .fetch();
    }

    @Override
    public List<PatEntity> findExpiredButNotYetDestroyed(Instant now, int limit) {
        // "Not yet destroyed" is secretDigest IS NOT NULL: the sweep's job is to clear digests on tokens
        // whose expiry has passed, so a row it has already handled must not come back on the next pass.
        return queryFactory.selectFrom(PAT)
                .where(PAT.expiresAt.isNotNull()
                        .and(PAT.expiresAt.before(now))
                        .and(PAT.secretDigest.isNotNull()))
                .limit(limit)
                .fetch();
    }

    @Override
    public List<PatEntity> findWithElapsedRotationOverlap(Instant now, int limit) {
        return queryFactory.selectFrom(PAT)
                .where(PAT.previousSecretDigest.isNotNull()
                        .and(PAT.previousSecretExpiresAt.isNotNull())
                        .and(PAT.previousSecretExpiresAt.before(now)))
                .limit(limit)
                .fetch();
    }

    @Override
    public List<PatEntity> findPurgeable(Instant purgeBefore, int limit) {
        // Purgeable means terminal AND past retention. A live token is never purgeable however old it is,
        // which is why this keys on the terminal timestamps rather than on createdAt.
        BooleanBuilder terminal = new BooleanBuilder()
                .or(PAT.revokedAt.isNotNull().and(PAT.revokedAt.before(purgeBefore)))
                .or(PAT.expiresAt.isNotNull().and(PAT.expiresAt.before(purgeBefore)));
        return queryFactory.selectFrom(PAT)
                .where(terminal)
                .limit(limit)
                .fetch();
    }

    @Override
    public List<PatEntity> findInactive(Instant cutoff, int limit) {
        // coalesce(lastUsedAt, createdAt) as a QueryDSL expression rather than two queries unioned, so a
        // never-used token and a long-quiet one come back together and the sweep has one code path.
        return queryFactory.selectFrom(PAT)
                .where(PAT.revokedAt.isNull()
                        .and(PAT.expiresAt.isNull().or(PAT.expiresAt.after(cutoff)))
                        .and(PAT.lastUsedAt.coalesce(PAT.createdAt).before(cutoff)))
                .limit(limit)
                .fetch();
    }

    @Override
    public List<PatEntity> findLiveByOwner(String ownerSubject, Instant now) {
        return queryFactory.selectFrom(PAT)
                .where(PAT.ownerSubject.eq(ownerSubject)
                        .and(PAT.revokedAt.isNull())
                        .and(PAT.expiresAt.isNull().or(PAT.expiresAt.after(now))))
                .fetch();
    }
}
