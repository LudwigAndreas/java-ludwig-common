package ru.ludwigandreas.identity.repository;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import ru.ludwigandreas.identity.entity.QSecurityPartnerEntity;
import ru.ludwigandreas.identity.entity.SecurityPartnerEntity;

/**
 * QueryDSL-JPA implementation, picked up by Spring Data through the {@code <Fragment>Impl} convention.
 *
 * <p>Every lookup filters on {@code status = ACTIVE} in the query rather than checking it afterwards, so
 * a suspended partner is indistinguishable from an unregistered one to everything upstream - the same
 * "certificate not recognized" answer, with no way to probe which partners exist by watching the
 * difference between the two responses.
 */
@RequiredArgsConstructor
class SecurityPartnerQueryRepositoryImpl implements SecurityPartnerQueryRepository {

    private static final QSecurityPartnerEntity PARTNER = QSecurityPartnerEntity.securityPartnerEntity;

    private final JPAQueryFactory queryFactory;

    @Override
    public Optional<SecurityPartnerEntity> lookupBySpiffeId(String spiffeId) {
        return lookup(PARTNER.spiffeId.eq(spiffeId));
    }

    @Override
    public Optional<SecurityPartnerEntity> lookupByDnsSan(String dnsSan) {
        return lookup(PARTNER.dnsSan.equalsIgnoreCase(dnsSan));
    }

    @Override
    public Optional<SecurityPartnerEntity> lookupBySubjectDn(String subjectDn) {
        return lookup(PARTNER.subjectDn.equalsIgnoreCase(subjectDn));
    }

    @Override
    public Optional<SecurityPartnerEntity> lookupByCode(String code) {
        return lookup(PARTNER.code.eq(code));
    }

    private Optional<SecurityPartnerEntity> lookup(BooleanExpression identifierMatches) {
        return Optional.ofNullable(queryFactory.selectFrom(PARTNER)
                .where(identifierMatches.and(
                        PARTNER.status.eq(ru.ludwigandreas.identity.entity.PartnerStatus.ACTIVE)))
                .fetchFirst());
    }
}
