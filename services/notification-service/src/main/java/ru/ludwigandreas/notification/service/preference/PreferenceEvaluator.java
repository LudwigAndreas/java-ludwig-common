package ru.ludwigandreas.notification.service.preference;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.ludwigandreas.notification.service.model.CategoryClass;
import ru.ludwigandreas.notification.service.model.ChannelClass;
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
 * <p>A {@link CategoryClass} answers two independent questions - whether opt-out is bypassed and
 * whether quiet hours are - and this method asks both of the class rather than comparing against a
 * named constant. If either decision were a boolean on the request, every calling service would set
 * it, and every calling service would set it to true, because from inside any one service its own
 * notification always looks important. Making it a property of the <em>category</em> means the
 * decision is made once, in configuration, by whoever owns the notification catalogue, and a service
 * that wants its campaign exempted has to argue for it rather than pass a flag.
 *
 * <p>The two questions were one until {@link CategoryClass#PLATFORM} existed. A release note is
 * undeclinable and not urgent, so it bypasses opt-out and honours quiet hours - a combination the
 * single question could not express. Asking the class rather than branching on constants is also
 * what keeps this method from growing an arm per class: there is deliberately no {@code switch} and
 * no {@code default} here, because a {@code default} would silently answer for the next class
 * somebody adds.
 *
 * <p>The suppression list is checked separately and has no bypass at all - see
 * {@link SuppressionService}. That is the one rule a transactional notification cannot override,
 * because a hard bounce is a fact about the address rather than a wish of its owner. It is also the
 * one preference-shaped thing this service still owns: a bounce is delivery state derived from
 * provider feedback only this service receives, and the user never chose it.
 *
 * <h2>Quiet hours apply to a channel that interrupts, and opt-out applies to all of them</h2>
 *
 * <p>These two rules look alike and are not. A recipient's opt-out is a statement about whether they
 * want a category at all, and it is honoured on every channel. A quiet window is narrower than that:
 * it exists so that nobody is woken, which is a statement about <em>delivery arriving
 * unrequested</em>. A notification that waits in an inbox until its owner chooses to look wakes
 * nobody, so deferring it to the next opening would delay it for no benefit and suppressing it
 * outright would discard it for no benefit at all.
 *
 * <p>So the quiet-hours branch is skipped for a {@link ChannelClass#PASSIVE} channel, and the
 * question is asked of {@link ChannelType#isPassive()} rather than of a named constant. That is not
 * stylistic. The same premise justifies three separate rules - this one, digest collapsing, and the
 * address suppression list - and writing each of them against a constant is how one declaration
 * decays into the six special cases the classification exists to replace. An ArchUnit rule in this
 * service's own test sources fails the build if any class in this package reads the {@code IN_APP}
 * constant.
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
        // Two questions asked of the class, not one asked of a constant. They were one answer while
        // TRANSACTIONAL was the only bypass; PLATFORM is undeclinable AND not urgent, which the
        // single question could not express. Asked of CategoryClass's own flags so that a fourth
        // class does not compile until both answers exist - see CategoryClass for the table.
        //
        // A recipient with no account has no preferences to consult, and none() answers "not opted
        // out" for every question - so there is no separate guard for the literal-address case.
        if (!categoryClass.bypassesOptOut() && preferences.optedOut(category, channel)) {
            return DispatchDecision.suppressed(DispatchDecision.Reasons.OPT_OUT);
        }
        if (categoryClass.bypassesQuietHours()) {
            return DispatchDecision.allowed();
        }
        // Opt-out above applies to every channel; quiet hours below apply only to a channel that
        // interrupts. Asked of the channel's class rather than of the channel, because the rule is
        // about interruption and not about any particular transport - see the class javadoc.
        if (channel.isPassive()) {
            return DispatchDecision.allowed();
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
