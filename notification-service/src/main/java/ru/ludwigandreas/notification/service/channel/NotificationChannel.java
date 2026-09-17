package ru.ludwigandreas.notification.service.channel;

import ru.ludwigandreas.notification.service.model.DeliveryResult;
import ru.ludwigandreas.notification.service.model.RenderedNotification;

/**
 * The extension point of this service: adding a transport is adding a bean.
 *
 * <p>Implementations are discovered from the context, so nothing in the dispatch path enumerates
 * channels or switches on a type. What that buys is that the interesting, hard-to-get-right code -
 * claiming, leasing, retrying, backing off, suppressing, recording - is written once and is the same
 * whatever the message goes out over, and a new channel inherits all of it by existing.
 *
 * <h2>The contract</h2>
 *
 * <ul>
 *   <li><b>No transaction is open</b> when {@link #send} is called, and an implementation must not
 *       open one. A provider call inside a transaction holds a database connection for the duration
 *       of somebody else's network, which is how a pool is exhausted by a slow SMTP relay.</li>
 *   <li><b>Everything needed is in the argument.</b> A {@link RenderedNotification} carries the
 *       address, the rendered parts and the correlation id; a channel never reads the database, which
 *       is what makes it unit-testable without a persistence context.</li>
 *   <li><b>Classify the failure.</b> Returning {@link DeliveryResult.Failed} with the wrong
 *       {@code FailureClass} is the most consequential mistake an implementation can make: too
 *       retryable and a malformed address is attempted eight times over two hours; too terminal and a
 *       restarting server permanently dead-letters everything in flight.</li>
 *   <li><b>Time out.</b> Every implementation must bound how long it can block. The dispatcher's
 *       lease is the backstop, not the control - a hung provider with no read timeout takes the poll
 *       thread with it.</li>
 * </ul>
 *
 * <p>Throwing is allowed and is treated as a retryable failure. That default is chosen deliberately:
 * an unclassified escape is more likely a bug or an infrastructure blip than a permanent rejection,
 * and a pointless retry costs less than a silently dead-lettered notification.
 */
public interface NotificationChannel {

    /**
     * Whether this bean handles the given transport.
     *
     * <p>A predicate rather than a {@code ChannelType type()} getter so one implementation can serve
     * several transports where they genuinely share a provider, without a second bean that differs
     * only in a constant.
     */
    boolean supports(ru.ludwigandreas.notification.service.model.ChannelType channelType);

    /** Human-readable name for logs and for the "no channel for X" diagnostic. */
    String name();

    /**
     * Sends one message.
     *
     * <p>Called with no ambient transaction. May block for at most the channel's configured timeout.
     */
    DeliveryResult send(RenderedNotification notification);
}
