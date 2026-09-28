package ru.ludwigandreas.notification.service.preference;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.ludwigandreas.notification.service.model.CategoryClass;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.settings.NotificationProperties;

/**
 * Decides whether a resolved recipient wants this notification, now.
 *
 * <p>Reads nothing, and knows nothing about addresses. Every input is a {@link RecipientPreferences}
 * resolved once for the whole fan-out of that recipient, plus the channel, the category and the
 * clock - which makes this a pure function, testable without a database and without a resolved
 * recipient to build. It used to query a preference table per delivery; the preferences now live in
 * the account service and reach this process through a local replica the settings module keeps.
 *
 * <p>Taking the preferences rather than the resolved recipient is also what keeps the dependency
 * one-way: recipient resolution produces preferences, and preferences know nothing about who is
 * being written to or where.
 *
 * <h2>The bypass, and why it is a category property rather than a flag on the request</h2>
 *
 * <p>A {@link CategoryClass#TRANSACTIONAL} notification ignores preferences and quiet hours. If that
 * decision were a boolean on the request, every calling service would set it, and every calling
 * service would set it to true - because from inside any one service its own notification always
 * looks important. Making it a property of the <em>category</em> means the decision is made once, in
 * configuration, by whoever owns the notification catalogue, and a service that wants its campaign
 * exempted has to argue for it rather than pass a flag.
 *
 * <p>The suppression list is checked separately and has no bypass at all - see
 * {@link SuppressionService}. That is the one rule a transactional notification cannot override,
 * because a hard bounce is a fact about the address rather than a wish of its owner. It is also the
 * one preference-shaped thing this service still owns: a bounce is delivery state derived from
 * provider feedback only this service receives, and the user never chose it.
 *
 * <h2>Precedence</h2>
 *
 * <p>Two settings can bear on one opt-out decision: the exact {@code (category, channel)} pair and
 * the blanket opt-out for the channel. The specific one wins whichever way it points, which is what
 * lets a recipient say "nothing at all, except order updates by email" without either setting having
 * to be deleted. See {@link RecipientPreferences#optedOut}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PreferenceEvaluator {

    private final NotificationProperties properties;

    /**
     * @param preferences   resolved once per recipient, before the fan-out reached any channel
     * @param channel       the channel this delivery would go out on
     * @param category      the business category, e.g. {@code order-updates}
     * @param categoryClass whether the recipient may decline it at all
     * @param now           evaluated against the recipient's quiet window in their own zone
     */
    public DispatchDecision evaluate(RecipientPreferences preferences, ChannelType channel,
                                     String category, CategoryClass categoryClass, Instant now) {
        if (categoryClass == CategoryClass.TRANSACTIONAL) {
            return DispatchDecision.allowed();
        }
        // A recipient with no account has no preferences to consult, and none() answers "not opted
        // out" for every question - so there is no separate guard for the literal-address case.
        if (preferences.optedOut(category, channel)) {
            return DispatchDecision.suppressed(DispatchDecision.Reasons.OPT_OUT);
        }
        return quietHoursDecision(preferences.quietHours(), now);
    }

    private DispatchDecision quietHoursDecision(QuietHours quietHours, Instant now) {
        if (!properties.getPreferences().isQuietHoursEnabled() || !quietHours.contains(now)) {
            return DispatchDecision.allowed();
        }
        if (!properties.getPreferences().isQuietHoursDefer()) {
            return DispatchDecision.suppressed(DispatchDecision.Reasons.QUIET_HOURS);
        }
        return DispatchDecision.deferred(quietHours.nextOpening(now),
                DispatchDecision.Reasons.QUIET_HOURS);
    }
}
