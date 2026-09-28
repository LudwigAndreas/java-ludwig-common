package ru.ludwigandreas.notification.service.recipient;

import java.util.Optional;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.model.RecipientRef;
import ru.ludwigandreas.notification.service.preference.RecipientPreferences;

/**
 * Turns "who" into "where, in what language, and when not to".
 *
 * <p>Split in two on purpose. {@link #preferences} is called once per recipient, before the fan-out
 * reaches any channel; {@link #resolve} is called once per recipient per channel and takes the
 * already-resolved preferences rather than fetching them again. The settings cache would make the
 * second shape cost the same today, but the interface is what keeps it that way when somebody adds a
 * fourth channel - and "resolve the preferences once per recipient" is a rule that has to be visible
 * in the signature to survive.
 *
 * <p>{@link #resolve} returns an {@link Optional} rather than throwing for the unreachable case,
 * because at fan-out an unreachable recipient is an ordinary outcome and not an error: a request
 * naming five people where one has no chat handle must produce four deliveries and one terminal
 * record, not a failed request. The synchronous REST path turns the empty result into a
 * {@code RecipientResolutionException} itself, where a caller is waiting and can act on it.
 */
public interface RecipientResolver {

    /**
     * The recipient's preferences, resolved once for the whole fan-out.
     *
     * <p>Never empty: a recipient with no account, no tenant or no stored preferences gets the
     * configured defaults. A missing preference must not silence a person.
     */
    RecipientPreferences preferences(RecipientRef ref);

    /**
     * The recipient's address on one channel, with their preferences already attached.
     *
     * @return the resolved recipient, or empty when there is no usable address for this channel
     */
    Optional<ResolvedRecipient> resolve(RecipientRef ref, ChannelType channel,
                                        RecipientPreferences preferences);
}
