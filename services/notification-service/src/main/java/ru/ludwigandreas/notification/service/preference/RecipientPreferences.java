package ru.ludwigandreas.notification.service.preference;

import java.time.ZoneId;
import java.util.Locale;
import ru.ludwigandreas.notification.service.model.ChannelType;

/**
 * One recipient's preferences, resolved once.
 *
 * <p>Built from a single {@link RecipientPreferenceSource#lookup} per recipient, held for the whole
 * fan-out of that recipient across every channel. Resolving per channel instead would be correct and
 * wasteful; resolving per setting would be an N+1 inside a loop over recipients, which is exactly
 * where one appears if the shape allows it.
 *
 * <p>Every type here is this service's own, including {@link OptOutMatrix} and {@link DigestMode}.
 * That is what lets the dispatch path be compiled and deployed without the user-settings module: the
 * adapter that reads that module translates into these types at the boundary, and nothing
 * downstream of it can tell which source answered.
 *
 * <p>Holding the matrix rather than copying every opt-out out of it is what keeps {@link #optedOut}
 * cheap: the opt-out a delivery needs depends on its category and channel, which are not known until
 * the fan-out reaches it.
 *
 * @param locale     the language to render in
 * @param zone       the zone quiet hours are evaluated in - never the server's
 * @param quietHours the recipient's window, already expressed in {@link #zone}
 * @param digest     how often they want batched notifications
 * @param optOuts    their opt-out answers, for the questions asked per delivery
 */
public record RecipientPreferences(
        Locale locale,
        ZoneId zone,
        QuietHours quietHours,
        DigestMode digest,
        OptOutMatrix optOuts) {

    public RecipientPreferences {
        optOuts = optOuts == null ? OptOutMatrix.NONE : optOuts;
        digest = digest == null ? DigestMode.IMMEDIATE : digest;
    }

    /**
     * Whether the recipient declined this category on this channel.
     *
     * <p>Two questions, most specific first: the opt-out for this exact category and channel, and
     * then the blanket opt-out for the channel. The specific one wins <em>whichever way it points</em>
     * - which is what lets a recipient say "nothing at all, except order updates by email" with two
     * settings and no deletion, and keeps the record of the blanket refusal intact.
     *
     * <p>"Not set" and "set to false" are different here, and the distinction is the whole mechanism:
     * an unset specific opt-out falls through to the blanket one, while an explicit
     * {@link OptOutState#OPTED_IN} overrides it. See {@link OptOutState}.
     */
    public boolean optedOut(String category, ChannelType channel) {
        OptOutState specific = optOuts.stateOf(category, channel);
        if (specific != OptOutState.UNSET) {
            return specific == OptOutState.OPTED_OUT;
        }
        return optOuts.stateOf(OptOutMatrix.ALL_CATEGORIES, channel) == OptOutState.OPTED_OUT;
    }

    /**
     * The preferences for a recipient with no stored preferences behind them - a literal address a
     * partner asked us to write to, or anybody at all in a deployment with no preference store.
     *
     * <p>Nothing is opted out and there are no quiet hours of their own: there is nobody whose
     * preferences these would be, or nowhere to read them from. The locale and zone come from the
     * caller or from configuration, which is the only information available in either case.
     */
    public static RecipientPreferences none(Locale locale, ZoneId zone) {
        return new RecipientPreferences(
                locale, zone, QuietHours.none(zone), DigestMode.IMMEDIATE, OptOutMatrix.NONE);
    }
}
