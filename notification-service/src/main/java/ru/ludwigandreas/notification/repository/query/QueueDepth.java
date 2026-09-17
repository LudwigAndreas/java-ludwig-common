package ru.ludwigandreas.notification.repository.query;

import ru.ludwigandreas.notification.repository.entity.ChannelKind;
import ru.ludwigandreas.notification.repository.entity.DeliveryPriority;

/**
 * How many deliveries are waiting in one channel's lane.
 *
 * <p>Returned as a typed record rather than as a QueryDSL {@code Tuple} so the metrics code that
 * consumes it cannot read the columns back in the wrong order - a mistake a {@code Tuple} makes
 * silently and that would publish a count under the wrong tag.
 *
 * @param count rows in {@code PENDING} or {@code FAILED}, the two states the claim query considers
 */
public record QueueDepth(ChannelKind channel, DeliveryPriority priority, long count) {
}
