package ru.ludwigandreas.notification.service.preference;

import ru.ludwigandreas.notification.service.model.ChannelType;

/**
 * One recipient's opt-out answers, resolved once and asked per delivery.
 *
 * <p>A function rather than a map because the question is only known at fan-out: the category comes
 * from the request and the channel from the delivery, and the alternative is a map built from every
 * category-by-channel combination whether or not that recipient's fan-out touches any of them.
 *
 * <p>The implementation behind it holds whatever the preference source returned - a projected
 * settings snapshot, or nothing at all - so no caller has to know which source answered.
 */
@FunctionalInterface
public interface OptOutMatrix {

    /**
     * The pseudo-category meaning "every declinable category on this channel".
     *
     * <p>A category name rather than a separate question, so the blanket opt-out is stored,
     * resolved and asked through exactly the same path as a real category - and so a preference
     * source has one shape to implement rather than two.
     */
    String ALL_CATEGORIES = "all";

    /** Nobody has declined anything: what a recipient with no stored preferences gets. */
    OptOutMatrix NONE = (category, channel) -> OptOutState.UNSET;

    /**
     * What the recipient said about this exact pair.
     *
     * @param category the business category, e.g. {@code order-updates}, or
     *                 {@link #ALL_CATEGORIES} for the blanket question
     * @return never null; {@link OptOutState#UNSET} when they have said nothing
     */
    OptOutState stateOf(String category, ChannelType channel);
}
