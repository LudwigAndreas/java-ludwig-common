package ru.ludwigandreas.identity.repository;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Predicate;
import java.time.Instant;
import ru.ludwigandreas.identity.entity.QSecurityGrantEntity;

/**
 * The predicates the administrative grant listing reads with, over the generated Q-type.
 */
public final class IdentityAdminQueries {

    private static final QSecurityGrantEntity GRANT = QSecurityGrantEntity.securityGrantEntity;

    private IdentityAdminQueries() {
    }

    /**
     * Grants matching the filter; every member is optional.
     *
     * @param subject  the grantee
     * @param resource the resource type
     * @param active   true for grants in force at {@code now}, false for lapsed and revoked ones, null for both
     * @param now      the instant "in force" is judged against
     * @return the predicate
     */
    public static Predicate grants(String subject, String resource, Boolean active, Instant now) {
        BooleanBuilder where = new BooleanBuilder();
        if (subject != null && !subject.isBlank()) {
            where.and(GRANT.subject.eq(subject));
        }
        if (resource != null && !resource.isBlank()) {
            where.and(GRANT.resourceType.eq(resource));
        }
        if (Boolean.TRUE.equals(active)) {
            where.and(GRANT.expiresAt.isNull().or(GRANT.expiresAt.after(now)));
        } else if (Boolean.FALSE.equals(active)) {
            where.and(GRANT.expiresAt.isNotNull().and(GRANT.expiresAt.loe(now)));
        }
        return where;
    }
}
