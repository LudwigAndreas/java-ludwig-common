package ru.ludwigandreas.notification.service.queue;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.notification.repository.NotificationDeliveryRepository;
import ru.ludwigandreas.notification.repository.entity.ChannelKind;
import ru.ludwigandreas.notification.repository.entity.DeliveryPriority;
import ru.ludwigandreas.notification.repository.entity.NotificationDeliveryEntity;
import ru.ludwigandreas.notification.service.model.ChannelType;

/**
 * The claim step, in its own short transaction.
 *
 * <p>Separate from {@code DeliveryDispatchService} for the reason the outbox module splits its
 * outcome recorder out: Spring's proxy-based AOP does not intercept a bean calling its own methods,
 * so a {@code @Transactional} claim invoked from another method of the same class would run with no
 * transaction and the mistake would be invisible. Keeping it in its own bean makes the boundary real.
 *
 * <p>Short is the operative word. The transaction covers exactly one statement and is committed
 * before anything is rendered or sent - a provider call must never happen with a transaction open,
 * because it would hold a pooled database connection for the duration of somebody else's network.
 */
@Service
@RequiredArgsConstructor
public class DeliveryClaimService {

    private final NotificationDeliveryRepository repository;

    /**
     * Leases up to {@code batchSize} due deliveries on one channel.
     *
     * @param minPriority the lane floor; the reserved high-priority pass raises it so a bulk backlog
     *                    cannot consume the budget a password reset needs
     */
    @Transactional
    public List<NotificationDeliveryEntity> claim(ChannelType channel, DeliveryPriority minPriority,
                                                  int batchSize, Instant now, String owner) {
        if (batchSize <= 0) {
            return List.of();
        }
        return repository.claimBatch(ChannelKind.valueOf(channel.name()).name(),
                minPriority.getWeight(), batchSize, now, owner);
    }

    /** Hands leases back on graceful shutdown, so a rolling deploy costs no latency. */
    @Transactional
    public long release(Collection<UUID> ids) {
        return repository.releaseClaimed(ids);
    }

    /** Crash recovery for leases an instance died holding. */
    @Transactional
    public long reclaimStale(Instant staleBefore) {
        return repository.reclaimStale(staleBefore);
    }
}
