package ru.ludwigandreas.identity.repository;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.identity.entity.SecurityUserEntity;
import ru.ludwigandreas.identity.entity.UserStatus;

/**
 * Users are always reached by their OIDC subject, which is the primary key, so {@code findById} is the
 * whole read API - no derived query method and no JPQL string anywhere in this module.
 */
public interface SecurityUserRepository extends BaseRepository<SecurityUserEntity, String> {

    /**
     * Users counted by source and status, with the time the newest applied change of each group happened.
     *
     * @return one row per source and status
     */
    @Query("select new ru.ludwigandreas.identity.repository.SecurityUserRepository$SourceCount("
            + "u.sourceSystem, u.status, count(u), max(u.sourceTimestamp))"
            + " from SecurityUserEntity u group by u.sourceSystem, u.status")
    List<SourceCount> countBySource();

    /**
     * One group of {@link #countBySource()}.
     *
     * @param source       the source
     * @param status       the status
     * @param users        users in the group
     * @param lastChangeAt when the group's newest applied change happened at the source, or null
     */
    record SourceCount(String source, UserStatus status, long users, Instant lastChangeAt) {
    }
}
