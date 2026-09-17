package ru.ludwigandreas.notification.repository;

import java.time.Instant;
import ru.ludwigandreas.notification.repository.entity.ChannelKind;

/** Giving permits back, and cleaning up windows nobody will ever read again. */
public interface RateLimitQueryRepository {

    /**
     * Returns permits reserved but not used, because the claim found fewer deliveries than the
     * reservation allowed for.
     *
     * <p>Without this, a quiet channel would burn its whole budget on empty batches and a burst
     * arriving late in the window would be throttled for no reason.
     */
    long releasePermits(ChannelKind channel, Instant windowStart, int permits);

    /** Deletes counter rows for windows that have closed. Runs under the distributed lock. */
    long purgeWindowsBefore(Instant cutoff);
}
