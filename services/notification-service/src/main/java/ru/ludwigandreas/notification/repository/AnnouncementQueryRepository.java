package ru.ludwigandreas.notification.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import ru.ludwigandreas.notification.repository.entity.AnnouncementEntity;
import ru.ludwigandreas.odatafilter.core.ODataQueryOptions;
import ru.ludwigandreas.odatafilter.execution.ODataPage;

/**
 * Every announcement read, as QueryDSL against the generated Q-type.
 *
 * <h2>Every visible-to method takes the caller's roles, and none of them defaults them</h2>
 *
 * <p>There is no overload that omits the role set and there must never be one. Visibility is derived
 * rather than stored, so unlike the inbox - where safety comes from a single obvious
 * {@code owner = me} - the correctness of these queries rests on the caller's roles reaching the
 * predicate. An overload that defaulted them to "none" would silently hide everything; one that
 * defaulted to "all" would silently show everything.
 *
 * <p>The roles are passed in rather than read from the security context here, because a repository
 * fragment that reached for the ambient principal could not be tested without one and would quietly
 * become unusable from the fan-out job, which has no caller.
 */
public interface AnnouncementQueryRepository {

    /**
     * One page of the announcements visible to a caller, with their {@code $filter} ANDed on.
     *
     * <p>Excludes anything they have dismissed and anything outside its window. The visibility terms
     * go into the {@code WHERE} clause rather than being applied to the fetched page, for the same
     * reason the inbox's owner predicate does: filtering afterwards returns fewer than {@code $top}
     * rows and a total that counts rows the caller cannot see.
     */
    ODataPage<AnnouncementEntity> searchVisible(String ownerUserId, Collection<String> roles,
                                                Instant now, ODataQueryOptions options);

    /**
     * One announcement, if this caller may see it.
     *
     * <p>Includes announcements the caller has dismissed, because dismissing is not deleting and a
     * client may hold a link to one. Empty both when the announcement does not exist and when it is
     * not visible to this caller - the caller must not be able to distinguish them.
     */
    Optional<AnnouncementEntity> findVisible(String ownerUserId, Collection<String> roles,
                                             UUID announcementId, Instant now);

    /** How many visible announcements this caller has not dismissed. */
    long countVisibleUndismissed(String ownerUserId, Collection<String> roles, Instant now);

    /**
     * One announcement by id, ignoring visibility, for an administrator.
     *
     * <p>Separate from {@link #findVisible} on purpose rather than being the same method with a flag:
     * a boolean parameter meaning "skip the security predicate" is the kind of argument that gets
     * passed {@code true} by mistake. A caller of this method is, by its name, doing so deliberately.
     */
    Optional<AnnouncementEntity> findForAdministration(UUID announcementId);

    /**
     * Announcements whose visibility ended before the given instant, for the retention purge.
     *
     * <p>Anchored on the end of visibility, not on creation: an announcement published for next
     * quarter has not started yet, and one still showing must never be purged out from under the
     * people reading it.
     */
    long purgeExpiredBefore(Instant visibleUntilBefore, int batchSize);
}
