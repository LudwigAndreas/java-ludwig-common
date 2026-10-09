package ru.ludwigandreas.notification.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import ru.ludwigandreas.notification.repository.entity.InboxItemEntity;
import ru.ludwigandreas.odatafilter.core.ODataQueryOptions;
import ru.ludwigandreas.odatafilter.execution.ODataPage;

/**
 * Every read and write of the inbox that is not a plain save, as QueryDSL against the generated
 * Q-type.
 *
 * <p>The method names deliberately avoid Spring Data's derived-query vocabulary, for the same reason
 * {@link DeliveryQueryRepository} does: a name that Spring Data would parse is a query that can
 * change meaning when the method is renamed.
 *
 * <h2>Every method takes the owner, and none of them defaults it</h2>
 *
 * <p>There is no overload without an {@code ownerUserId} and there must never be one. The owner is
 * the single predicate that makes any of these queries safe, and an overload that omitted it would
 * be one import away from being the one somebody calls. The parameter is first on every signature so
 * that a call site reads as "this owner's items" rather than as a filter applied to all of them.
 */
public interface InboxQueryRepository {

    /**
     * One page of the owner's items, with the caller's {@code $filter} ANDed onto the owner
     * predicate.
     *
     * <p>The owner predicate goes into the {@code WHERE} clause rather than being applied to the
     * fetched page, which is the same placement argument the delivery search makes: filtering
     * afterwards would return fewer than {@code $top} rows and a total that counted other people's
     * items, so both the page and the pager would be wrong, and the database would have read and
     * shipped those rows anyway.
     */
    ODataPage<InboxItemEntity> search(String ownerUserId, ODataQueryOptions options);

    /**
     * One of the owner's items by id.
     *
     * <p>Empty both when the item does not exist and when it belongs to somebody else, and the
     * caller must not distinguish them. That is not laziness about error messages: an endpoint that
     * answered "403" for one and "404" for the other would confirm the existence of other people's
     * notifications to anybody willing to guess an id.
     */
    Optional<InboxItemEntity> findOwned(String ownerUserId, UUID itemId);

    /** How many of the owner's items are unread and not dismissed. */
    long countUnread(String ownerUserId);

    /**
     * Marks every one of the owner's unread items read.
     *
     * @return how many rows moved, which is zero on a repeat rather than an error
     */
    long markAllRead(String ownerUserId, Instant readAt);

    /**
     * Items whose retention window has closed, measured from when they were read or dismissed.
     *
     * <p>An item that has been neither is never returned by this, whatever its age - which is the
     * single most important property of the inbox's retention and the reason it has its own sweep
     * rather than sharing the delivery's.
     */
    long purgeSettledBefore(Instant settledBefore, int batchSize);

    /**
     * Items that have never been read and are older than a configured ceiling.
     *
     * <p>Separate from {@link #purgeSettledBefore} on purpose. This one discards notifications their
     * recipients were meant to receive and never saw, so it is off unless a deployment asks for it
     * and it is counted separately - "we discarded N unread notifications" is the one retention
     * number an operator has to be able to see.
     */
    long purgeUnreadBefore(Instant createdBefore, int batchSize);
}
