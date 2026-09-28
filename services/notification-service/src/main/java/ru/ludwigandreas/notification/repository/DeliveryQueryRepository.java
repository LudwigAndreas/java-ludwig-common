package ru.ludwigandreas.notification.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import ru.ludwigandreas.notification.repository.entity.ChannelKind;
import ru.ludwigandreas.notification.repository.entity.DeliveryStatusHistoryEntity;
import ru.ludwigandreas.notification.repository.entity.NotificationDeliveryEntity;
import ru.ludwigandreas.notification.repository.query.DeliverySearchCriteria;
import ru.ludwigandreas.notification.repository.query.QueueDepth;

/**
 * Every delivery query this service runs, in one fragment.
 *
 * <p>The method names deliberately avoid Spring Data's derived-query vocabulary
 * ({@code findByStatusAndNextAttemptAtBefore}, ...): those are parsed from the method name at
 * context startup and fail at runtime on a typo or a renamed field. Everything here is QueryDSL
 * against generated Q-types instead, so a renamed or retyped column breaks the compile.
 *
 * <p>Callers must supply the transaction. A repository fragment's implementation is not proxied with
 * {@code @Transactional} the way {@code SimpleJpaRepository}'s own methods are, so the mutating
 * methods below ({@link #reclaimStale}, {@link #releaseClaimed}, the purges) are only correct when
 * invoked from an already-transactional service method - which is where they are called from.
 */
public interface DeliveryQueryRepository {

    /** Runs the admin OData search, already narrowed to the caller's data scope. */
    Page<NotificationDeliveryEntity> search(DeliverySearchCriteria criteria);

    /** The status trail of one delivery, oldest first. */
    List<DeliveryStatusHistoryEntity> historyOf(UUID deliveryId);

    List<NotificationDeliveryEntity> findByRequestId(UUID requestId);

    /** Matches an inbound provider receipt back to the delivery that produced it. */
    Optional<NotificationDeliveryEntity> findByProviderMessageId(ChannelKind channel, String providerMessageId);

    /** The per-delivery duplicate check that backs the unique index on {@code dedup_key}. */
    Optional<NotificationDeliveryEntity> findByDedupKey(String dedupKey);

    /**
     * Crash recovery: returns deliveries still {@code CLAIMED} with a lease older than
     * {@code staleBefore} to {@code PENDING}.
     *
     * <p>Expressed as a QueryDSL bulk update rather than JPQL, so the column names are checked at
     * compile time. It intentionally does not bump {@code attempts}: a pod that died before
     * dispatching never made an attempt, and counting one would burn a retry for an infrastructure
     * failure that has nothing to do with the recipient.
     *
     * @return how many leases were reclaimed
     */
    long reclaimStale(Instant staleBefore);

    /**
     * Returns leases this instance still holds but will not process, used on graceful shutdown.
     *
     * <p>The stale sweeper would eventually recover them anyway, but "eventually" is one stale
     * timeout - deliberately minutes, because it has to be longer than the slowest legitimate
     * dispatch. Releasing on the way out turns a rolling deploy's latency spike into nothing.
     */
    long releaseClaimed(Collection<UUID> ids);

    /** Queue depth per channel and lane - one of the two SLO signals worth alerting on. */
    List<QueueDepth> queueDepth();

    /**
     * Creation time of the oldest claimable delivery, or empty when the queue is drained.
     *
     * <p>The other SLO signal, and the more useful of the two: depth tells you how much is waiting,
     * age tells you whether anything is moving. A queue holding 50 rows that are all four seconds
     * old is healthy; one holding 50 rows that are an hour old is an outage.
     */
    Optional<Instant> oldestClaimableCreatedAt();

    /** Digest groups with at least one delivery whose window has closed. */
    List<String> dueDigestGroups(Instant now, int limit);

    /** The batched deliveries of one digest group whose window has closed, oldest first. */
    List<NotificationDeliveryEntity> dueBatchedIn(String digestGroup, Instant now);

    /**
     * Clears the recipient address and rendered content of deliveries settled before the cut-off,
     * leaving the operational metadata.
     *
     * <p>Separate from {@link #purgeSettledBefore} because the two retention windows are genuinely
     * different: how long we may keep somebody's address is a privacy question, and how long
     * operations needs to know that a delivery happened is a capacity question. Collapsing them
     * forces the privacy answer to be as long as the operational one.
     *
     * @return how many deliveries were scrubbed
     */
    long scrubRecipientDataBefore(Instant cutoff);

    /** Deletes settled deliveries older than the cut-off. Content rows cascade with them. */
    long purgeSettledBefore(Instant cutoff);

    /** Deletes status-history rows older than the cut-off, whose deliveries may already be gone. */
    long purgeHistoryBefore(Instant cutoff);

    /** Deletes rendered content whose own {@code purge_after} has passed. */
    long purgeContentDue(Instant now);
}
