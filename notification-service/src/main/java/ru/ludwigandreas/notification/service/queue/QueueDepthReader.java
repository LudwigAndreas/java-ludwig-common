package ru.ludwigandreas.notification.service.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.notification.repository.NotificationDeliveryRepository;
import ru.ludwigandreas.notification.repository.query.QueueDepth;

/**
 * The two SLO readings, in read-only transactions of their own.
 *
 * <p>Its own bean rather than methods on the scheduler, for the reason that keeps recurring here:
 * Spring's transactional proxy does not intercept self-invocation, so a {@code @Transactional} read
 * called from the scheduler's own poll method would run without one - and a read outside a
 * transaction on a lazily-initialised context is the kind of thing that works until it does not.
 *
 * <p>Shared by the metrics scheduler and the readiness probe, which is also why it is separate: both
 * want the same number and neither should be computing it independently.
 */
@Service
@RequiredArgsConstructor
public class QueueDepthReader {

    private final NotificationDeliveryRepository repository;

    /** Rows waiting per channel and lane. Lanes with nothing in them are simply absent. */
    @Transactional(readOnly = true)
    public List<QueueDepth> queueDepth() {
        return repository.queueDepth();
    }

    /**
     * How long the oldest claimable delivery has been waiting, or {@link Duration#ZERO} when the
     * queue is drained.
     *
     * <p>Zero rather than an empty Optional, because every consumer would otherwise have to decide
     * what an absent age means, and the two plausible answers - zero and infinity - are opposites. An
     * empty queue has been waiting for no time at all.
     */
    @Transactional(readOnly = true)
    public Duration oldestPendingAge() {
        return repository.oldestClaimableCreatedAt()
                .map(oldest -> Duration.between(oldest, Instant.now()))
                .filter(age -> !age.isNegative())
                .orElse(Duration.ZERO);
    }
}
