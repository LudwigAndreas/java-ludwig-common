package ru.ludwigandreas.notification.service.queue;

import java.time.Instant;
import ru.ludwigandreas.notification.service.model.ChannelType;

/**
 * How many messages this channel may still send right now, across the whole deployment.
 *
 * <p>Reserved in batch rather than consumed per message, because the poller has to decide how many
 * deliveries to claim <em>before</em> it claims them - claiming a hundred and then discovering the
 * budget was ten would mean ninety leases taken and immediately released, every cycle.
 */
public interface ChannelRateLimiter {

    /**
     * Reserves up to {@code requested} permits.
     *
     * @return how many were granted, possibly zero, never more than requested
     */
    int reserve(ChannelType channel, int requested, Instant now);

    /**
     * Gives back permits that were reserved and not used.
     *
     * <p>Without this a quiet channel burns its whole budget on empty batches, and a burst arriving
     * late in the window is throttled for no reason at all.
     */
    void release(ChannelType channel, int permits, Instant now);

    /** Start of the window {@code at} falls in - the key a reservation is recorded against. */
    Instant windowStart(Instant at);
}
