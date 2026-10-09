package ru.ludwigandreas.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.notification.service.model.CategoryClass;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.preference.DispatchDecision;
import ru.ludwigandreas.notification.service.preference.OptOutMatrix;
import ru.ludwigandreas.notification.service.preference.OptOutState;
import ru.ludwigandreas.notification.service.preference.PreferenceEvaluator;
import ru.ludwigandreas.notification.service.preference.QuietHours;
import ru.ludwigandreas.notification.service.preference.RecipientPreferences;
import ru.ludwigandreas.notification.settings.NotificationProperties;

/**
 * What a passive channel changes about dispatch eligibility, and what it deliberately does not.
 *
 * <p>The two rules in {@link PreferenceEvaluator} look alike and diverge here, which is the whole
 * reason these tests exist as a pair rather than as one. A recipient's <b>opt-out</b> is a statement
 * about whether they want a category at all, so it is honoured on the inbox exactly as on email. A
 * <b>quiet window</b> is narrower: it exists so that nobody is woken, and a notification that waits
 * to be read wakes nobody - so applying it to the inbox would delay a notification for no benefit,
 * or discard it for none at all.
 *
 * <p>Getting this backwards is a silent defect in both directions. Applying quiet hours to the
 * inbox makes a notification arrive hours late for no reason anybody can see; not applying opt-out
 * to the inbox means a recipient who declined a category keeps receiving it in a different place,
 * which is worse than the original problem.
 */
class PreferenceEvaluatorTest {

    private static final String CATEGORY = "order-updates";
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");

    /** 02:00 Moscow - inside the 22:00-08:00 window below. */
    private static final Instant NIGHT = Instant.parse("2026-10-09T23:00:00Z");

    private final PreferenceEvaluator evaluator = new PreferenceEvaluator(quietHoursEnabled());

    @Nested
    @DisplayName("quiet hours")
    class QuietHoursApplicability {

        @Test
        @DisplayName("do not defer or suppress a passive channel")
        void passiveChannelIgnoresQuietHours() {
            DispatchDecision decision = evaluator.evaluate(
                    withQuietHours(), ChannelType.IN_APP, CATEGORY, CategoryClass.MARKETING, NIGHT);

            assertThat(decision).isInstanceOf(DispatchDecision.Allowed.class);
        }

        /**
         * The control. Without this the test above would pass just as well against a clock outside
         * the window, or against quiet hours switched off - and would prove nothing.
         */
        @Test
        @DisplayName("still defer an interrupting channel at the same instant")
        void interruptingChannelIsStillDeferred() {
            DispatchDecision decision = evaluator.evaluate(
                    withQuietHours(), ChannelType.EMAIL, CATEGORY, CategoryClass.MARKETING, NIGHT);

            assertThat(decision).isInstanceOf(DispatchDecision.Deferred.class);
            assertThat(((DispatchDecision.Deferred) decision).reason())
                    .isEqualTo(DispatchDecision.Reasons.QUIET_HOURS);
        }
    }

    @Nested
    @DisplayName("opt-out")
    class OptOutApplicability {

        @Test
        @DisplayName("suppresses a passive channel for the exact category and channel pair")
        void specificOptOutSuppressesPassiveChannel() {
            DispatchDecision decision = evaluator.evaluate(
                    optedOutOf(CATEGORY, ChannelType.IN_APP), ChannelType.IN_APP, CATEGORY,
                    CategoryClass.MARKETING, NIGHT);

            assertThat(decision).isInstanceOf(DispatchDecision.Suppressed.class);
            assertThat(((DispatchDecision.Suppressed) decision).reason())
                    .isEqualTo(DispatchDecision.Reasons.OPT_OUT);
        }

        @Test
        @DisplayName("suppresses a passive channel under the blanket opt-out")
        void blanketOptOutSuppressesPassiveChannel() {
            DispatchDecision decision = evaluator.evaluate(
                    optedOutOf(OptOutMatrix.ALL_CATEGORIES, ChannelType.IN_APP), ChannelType.IN_APP,
                    CATEGORY, CategoryClass.MARKETING, NIGHT);

            assertThat(decision).isInstanceOf(DispatchDecision.Suppressed.class);
        }

        /**
         * "Nothing at all, except order updates" - the precedence rule, asserted on the passive
         * channel specifically, because an explicit opt-in overriding a blanket refusal is the case
         * a channel-wide short-circuit would quietly break.
         */
        @Test
        @DisplayName("lets an explicit opt-in override the blanket refusal on a passive channel")
        void explicitOptInWinsOnPassiveChannel() {
            RecipientPreferences preferences = preferences((category, channel) -> {
                if (channel != ChannelType.IN_APP) {
                    return OptOutState.UNSET;
                }
                if (CATEGORY.equals(category)) {
                    return OptOutState.OPTED_IN;
                }
                return OptOutMatrix.ALL_CATEGORIES.equals(category)
                        ? OptOutState.OPTED_OUT : OptOutState.UNSET;
            }, QuietHours.none(ZONE));

            assertThat(evaluator.evaluate(preferences, ChannelType.IN_APP, CATEGORY,
                    CategoryClass.MARKETING, NIGHT))
                    .isInstanceOf(DispatchDecision.Allowed.class);
            assertThat(evaluator.evaluate(preferences, ChannelType.IN_APP, "promotions",
                    CategoryClass.MARKETING, NIGHT))
                    .isInstanceOf(DispatchDecision.Suppressed.class);
        }

        @Test
        @DisplayName("is bypassed on a passive channel by an undeclinable category")
        void transactionalBypassesOptOutOnPassiveChannel() {
            DispatchDecision decision = evaluator.evaluate(
                    optedOutOf(OptOutMatrix.ALL_CATEGORIES, ChannelType.IN_APP), ChannelType.IN_APP,
                    CATEGORY, CategoryClass.TRANSACTIONAL, NIGHT);

            assertThat(decision).isInstanceOf(DispatchDecision.Allowed.class);
        }
    }

    private static RecipientPreferences withQuietHours() {
        return preferences(OptOutMatrix.NONE,
                new QuietHours(LocalTime.of(22, 0), LocalTime.of(8, 0), ZONE));
    }

    private static RecipientPreferences optedOutOf(String category, ChannelType channel) {
        return preferences(
                (askedCategory, askedChannel) ->
                        category.equals(askedCategory) && channel == askedChannel
                                ? OptOutState.OPTED_OUT : OptOutState.UNSET,
                QuietHours.none(ZONE));
    }

    private static RecipientPreferences preferences(OptOutMatrix optOuts, QuietHours quietHours) {
        return new RecipientPreferences(Locale.ENGLISH, ZONE, quietHours, null, optOuts);
    }

    private static NotificationProperties quietHoursEnabled() {
        NotificationProperties properties = new NotificationProperties();
        properties.getPreferences().setQuietHoursEnabled(true);
        properties.getPreferences().setQuietHoursDefer(true);
        return properties;
    }
}
