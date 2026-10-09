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

    /** 15:00 Moscow - outside the window, so a deferral there would be the test's own fault. */
    private static final Instant NOON = Instant.parse("2026-10-09T12:00:00Z");

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

    /**
     * The whole three-by-two matrix in one place, including the two rows that must not have changed.
     *
     * <p>Splitting one bypass into two is the kind of edit that is behaviour-preserving right up
     * until it is not, and the failure is silent in both directions: a lost opt-out bypass sends
     * nothing at all for transactional mail, and a gained quiet-hours bypass starts emailing people
     * at three in the morning. The `TRANSACTIONAL` and `MARKETING` rows below are controls - if the
     * split had collapsed back into a single question, the `PLATFORM` row would still pass and these
     * would not.
     */
    @Nested
    @DisplayName("the category-class bypass matrix")
    class BypassMatrix {

        @Test
        @DisplayName("TRANSACTIONAL bypasses both, unchanged")
        void transactionalBypassesBoth() {
            assertThat(CategoryClass.TRANSACTIONAL.bypassesOptOut()).isTrue();
            assertThat(CategoryClass.TRANSACTIONAL.bypassesQuietHours()).isTrue();

            // Opted out of the category AND inside the quiet window: still allowed.
            assertThat(evaluator.evaluate(optedOutAndQuiet(), ChannelType.EMAIL, CATEGORY,
                    CategoryClass.TRANSACTIONAL, NIGHT))
                    .isInstanceOf(DispatchDecision.Allowed.class);
        }

        @Test
        @DisplayName("MARKETING bypasses neither, unchanged")
        void marketingBypassesNeither() {
            assertThat(CategoryClass.MARKETING.bypassesOptOut()).isFalse();
            assertThat(CategoryClass.MARKETING.bypassesQuietHours()).isFalse();

            assertThat(evaluator.evaluate(optedOutOf(CATEGORY, ChannelType.EMAIL),
                    ChannelType.EMAIL, CATEGORY, CategoryClass.MARKETING, NIGHT))
                    .isInstanceOf(DispatchDecision.Suppressed.class);
            assertThat(evaluator.evaluate(withQuietHours(), ChannelType.EMAIL, CATEGORY,
                    CategoryClass.MARKETING, NIGHT))
                    .isInstanceOf(DispatchDecision.Deferred.class);
        }

        @Test
        @DisplayName("PLATFORM bypasses opt-out")
        void platformBypassesOptOut() {
            assertThat(CategoryClass.PLATFORM.bypassesOptOut()).isTrue();

            assertThat(evaluator.evaluate(optedOutOf(CATEGORY, ChannelType.EMAIL),
                    ChannelType.EMAIL, CATEGORY, CategoryClass.PLATFORM, NOON))
                    .as("the organisation has decided they should know")
                    .isInstanceOf(DispatchDecision.Allowed.class);
        }

        /** The cell the old two-class model had no way to express. */
        @Test
        @DisplayName("PLATFORM honours quiet hours")
        void platformHonoursQuietHours() {
            assertThat(CategoryClass.PLATFORM.bypassesQuietHours()).isFalse();

            assertThat(evaluator.evaluate(withQuietHours(), ChannelType.EMAIL, CATEGORY,
                    CategoryClass.PLATFORM, NIGHT))
                    .as("undeclinable is not the same as urgent")
                    .isInstanceOf(DispatchDecision.Deferred.class);
        }

        /**
         * Both at once, which is the case that distinguishes a real split from two flags that happen
         * to agree: the opt-out is bypassed and the quiet window is still honoured, in one decision.
         */
        @Test
        @DisplayName("PLATFORM for an opted-out recipient inside quiet hours is deferred, not suppressed")
        void platformDefersRatherThanSuppresses() {
            DispatchDecision decision = evaluator.evaluate(optedOutAndQuiet(), ChannelType.EMAIL,
                    CATEGORY, CategoryClass.PLATFORM, NIGHT);

            assertThat(decision).isInstanceOf(DispatchDecision.Deferred.class);
            assertThat(((DispatchDecision.Deferred) decision).reason())
                    .isEqualTo(DispatchDecision.Reasons.QUIET_HOURS);
        }

        /**
         * Quiet hours never applied to a passive channel, so this answer changes nothing for the
         * inbox half of an announcement - only for its email half.
         */
        @Test
        @DisplayName("PLATFORM on a passive channel is allowed even inside quiet hours")
        void platformOnPassiveChannelIsAllowed() {
            assertThat(evaluator.evaluate(optedOutAndQuiet(), ChannelType.IN_APP, CATEGORY,
                    CategoryClass.PLATFORM, NIGHT))
                    .isInstanceOf(DispatchDecision.Allowed.class);
        }

        @Test
        @DisplayName("every class declares both answers")
        void everyClassDeclaresBothAnswers() {
            for (CategoryClass categoryClass : CategoryClass.values()) {
                // Reading them is the assertion: they are primitives set from mandatory constructor
                // arguments, so a class added without them does not compile. This asserts the set is
                // exhaustively covered by the rows above rather than that the getters work.
                categoryClass.bypassesOptOut();
                categoryClass.bypassesQuietHours();
            }
            assertThat(CategoryClass.values()).hasSize(3);
        }
    }

    /** Opted out of the category on email, and inside the quiet window. */
    private static RecipientPreferences optedOutAndQuiet() {
        return preferences(
                (askedCategory, askedChannel) -> CATEGORY.equals(askedCategory)
                        ? OptOutState.OPTED_OUT : OptOutState.UNSET,
                new QuietHours(LocalTime.of(22, 0), LocalTime.of(8, 0), ZONE));
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
