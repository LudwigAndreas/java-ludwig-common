package ru.ludwigandreas.notification.service.queue;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.notification.repository.RateLimitWindowRepository;
import ru.ludwigandreas.notification.repository.entity.ChannelKind;
import ru.ludwigandreas.notification.service.channel.ChannelRuntime;
import ru.ludwigandreas.notification.service.model.ChannelType;

/**
 * A fixed-window counter in the database, so the configured rate is the actual rate.
 *
 * <h2>Why not a token bucket in memory</h2>
 *
 * <p>Because it would be wrong at more than one replica, and wrong in the direction nobody notices:
 * three pods each holding a 100-per-minute bucket send 300 per minute. The number in the
 * configuration file would mean nothing, and it would quietly change meaning every time the
 * deployment scaled - so a provider contract negotiated at 100/minute would be violated by an
 * autoscaler. Multi-replica safety is a hard requirement of this service, and a per-process limiter
 * does not satisfy it.
 *
 * <h2>Why a fixed window and not a sliding one</h2>
 *
 * <p>A fixed window is one row and one statement. A sliding window needs either a sorted set of
 * timestamps or a second row per sub-window, and buys precision at a boundary that a short window
 * already makes small: with a ten-second window the worst case is a burst straddling the boundary
 * briefly reaching twice the nominal rate, which every provider tolerates and no amount of
 * additional machinery here is worth avoiding.
 *
 * <h2>Behaviour at three replicas</h2>
 *
 * <p>The inner {@code FOR UPDATE} in {@link RateLimitWindowRepository#reserve} serializes the three
 * reservations against each other. Each one sees the committed total, takes what is left up to its
 * request, and reports back exactly what it got - so the sum across replicas never exceeds the limit.
 * A replica granted zero simply claims nothing this cycle and tries again on the next.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostgresChannelRateLimiter implements ChannelRateLimiter {

    /** Reservation result meaning "the counter row for this window does not exist yet". */
    private static final Integer NO_WINDOW = null;

    private final RateLimitWindowRepository repository;
    private final ChannelRuntime channelRuntime;
    private final NotificationProperties properties;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int reserve(ChannelType channel, int requested, Instant now) {
        int limit = channelRuntime.maxPerWindow(channel);
        if (limit <= 0) {
            // Unlimited. Deliberately short-circuited before touching the database: a channel whose
            // provider is inside the cluster should not pay a statement per poll cycle to be told it
            // has no limit.
            return requested;
        }
        Instant windowStart = windowStart(now);
        String channelName = ChannelKind.valueOf(channel.name()).name();

        Integer granted = repository.reserve(channelName, windowStart, requested, limit);
        if (granted == NO_WINDOW) {
            // First reservation in this window. Creating the row is idempotent, and the retry then
            // takes the ordinary path - so this costs one extra statement per window per channel
            // rather than one per cycle.
            repository.ensureWindow(UUID.randomUUID(), channelName, windowStart);
            granted = repository.reserve(channelName, windowStart, requested, limit);
        }
        return granted == null ? 0 : granted;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(ChannelType channel, int permits, Instant now) {
        if (permits <= 0 || channelRuntime.maxPerWindow(channel) <= 0) {
            return;
        }
        repository.releasePermits(ChannelKind.valueOf(channel.name()), windowStart(now), permits);
    }

    @Override
    public Instant windowStart(Instant at) {
        long windowMillis = Math.max(1L, properties.getChannels().getRateLimitWindow().toMillis());
        return Instant.ofEpochMilli(at.toEpochMilli() / windowMillis * windowMillis);
    }
}
