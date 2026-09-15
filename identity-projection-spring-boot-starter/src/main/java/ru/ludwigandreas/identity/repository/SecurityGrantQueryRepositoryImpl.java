package ru.ludwigandreas.identity.repository;

import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import ru.ludwigandreas.identity.entity.QSecurityGrantEntity;
import ru.ludwigandreas.identity.entity.SecurityGrantEntity;
import ru.ludwigandreas.security.principal.PrincipalType;

@RequiredArgsConstructor
class SecurityGrantQueryRepositoryImpl implements SecurityGrantQueryRepository {

    private static final QSecurityGrantEntity GRANT = QSecurityGrantEntity.securityGrantEntity;

    private final JPAQueryFactory queryFactory;

    /**
     * Expiry is evaluated against the database clock's view of "now" passed in from the application, and
     * a null {@code expires_at} means "does not expire". Both halves matter: filtering in the query keeps
     * an expired grant from ever reaching the scope union, and treating null as permanent avoids the
     * alternative convention (a far-future sentinel date) that quietly becomes wrong.
     */
    @Override
    public List<SecurityGrantEntity> activeGrants(String subject, PrincipalType principalType,
                                                  String resourceType, String action) {
        Instant now = Instant.now();
        return queryFactory.selectFrom(GRANT)
                .where(GRANT.subject.eq(subject)
                        .and(GRANT.principalType.eq(principalType))
                        .and(GRANT.resourceType.eq(resourceType))
                        .and(GRANT.action.eq(action))
                        .and(GRANT.expiresAt.isNull().or(GRANT.expiresAt.after(now))))
                .fetch();
    }
}
