package ru.ludwigandreas.notification.service.recipient;

import java.util.Optional;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.model.RecipientRef;

/**
 * Turns "who" into "where, in what language, and when not to".
 *
 * <p>Returns an {@link Optional} rather than throwing for the unreachable case, because at fan-out
 * an unreachable recipient is an ordinary outcome and not an error: a request naming five people
 * where one has no chat handle must produce four deliveries and one terminal record, not a failed
 * request. The synchronous REST path turns the empty result into a
 * {@code RecipientResolutionException} itself, where a caller is waiting and can act on it.
 */
public interface RecipientResolver {

    /**
     * @return the resolved recipient, or empty when there is no usable address for this channel
     */
    Optional<ResolvedRecipient> resolve(RecipientRef ref, ChannelType channel);
}
