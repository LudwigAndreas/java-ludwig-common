package ru.ludwigandreas.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.preference.OptOutMatrix;
import ru.ludwigandreas.notification.service.preference.OptOutState;
import ru.ludwigandreas.notification.service.preference.RecipientPreferences;

/**
 * Opt-out precedence, which is the one piece of preference logic that is genuinely easy to get
 * wrong and impossible to notice when it is.
 *
 * <p>Every case here is about the three-state answer surviving. Collapse {@link OptOutState#UNSET}
 * and {@link OptOutState#OPTED_IN} into one boolean and four of these tests still pass - the fifth,
 * the recipient who declined everything except one category, is the one that silently stops
 * receiving the thing they asked to keep.
 */
class RecipientPreferencesTest {

    private static final String CATEGORY = "order-updates";

    @Test
    @DisplayName("a recipient with no stored preferences has declined nothing")
    void noPreferencesDeclinesNothing() {
        assertThat(none().optedOut(CATEGORY, ChannelType.EMAIL)).isFalse();
    }

    @Test
    @DisplayName("an opt-out for this exact category and channel suppresses it")
    void specificOptOut() {
        RecipientPreferences preferences = with(Map.of(key(CATEGORY, ChannelType.EMAIL),
                OptOutState.OPTED_OUT));

        assertThat(preferences.optedOut(CATEGORY, ChannelType.EMAIL)).isTrue();
        // and nothing else
        assertThat(preferences.optedOut(CATEGORY, ChannelType.CHAT)).isFalse();
        assertThat(preferences.optedOut("security", ChannelType.EMAIL)).isFalse();
    }

    @Test
    @DisplayName("a blanket opt-out covers a category that was never named")
    void blanketOptOut() {
        RecipientPreferences preferences = with(Map.of(
                key(OptOutMatrix.ALL_CATEGORIES, ChannelType.EMAIL), OptOutState.OPTED_OUT));

        assertThat(preferences.optedOut("a-category-nobody-declared", ChannelType.EMAIL)).isTrue();
        assertThat(preferences.optedOut(CATEGORY, ChannelType.CHAT)).isFalse();
    }

    /**
     * The case the three-state answer exists for: "stop sending me things, except order updates".
     *
     * <p>Expressed as two settings and no deletion, so the blanket refusal stays on record. With a
     * plain boolean the explicit opt-in is indistinguishable from never having answered, it falls
     * through to the blanket opt-out, and the one category the recipient asked to keep is the one
     * they stop getting.
     */
    @Test
    @DisplayName("an explicit opt-in beats a blanket opt-out")
    void explicitOptInBeatsBlanket() {
        RecipientPreferences preferences = with(Map.of(
                key(OptOutMatrix.ALL_CATEGORIES, ChannelType.EMAIL), OptOutState.OPTED_OUT,
                key(CATEGORY, ChannelType.EMAIL), OptOutState.OPTED_IN));

        assertThat(preferences.optedOut(CATEGORY, ChannelType.EMAIL)).isFalse();
        assertThat(preferences.optedOut("marketing", ChannelType.EMAIL)).isTrue();
    }

    @Test
    @DisplayName("a specific opt-out still applies where the blanket says nothing")
    void specificOptOutWithoutBlanket() {
        RecipientPreferences preferences = with(Map.of(
                key(CATEGORY, ChannelType.EMAIL), OptOutState.OPTED_OUT,
                key(OptOutMatrix.ALL_CATEGORIES, ChannelType.EMAIL), OptOutState.OPTED_IN));

        assertThat(preferences.optedOut(CATEGORY, ChannelType.EMAIL)).isTrue();
        assertThat(preferences.optedOut("marketing", ChannelType.EMAIL)).isFalse();
    }

    private static String key(String category, ChannelType channel) {
        return category + "|" + channel;
    }

    private static RecipientPreferences with(Map<String, OptOutState> answers) {
        Map<String, OptOutState> copy = new HashMap<>(answers);
        OptOutMatrix matrix = (category, channel) ->
                copy.getOrDefault(key(category, channel), OptOutState.UNSET);
        return new RecipientPreferences(Locale.ENGLISH, ZoneOffset.UTC,
                ru.ludwigandreas.notification.service.preference.QuietHours.none(ZoneOffset.UTC),
                null, matrix);
    }

    private static RecipientPreferences none() {
        return RecipientPreferences.none(Locale.ENGLISH, ZoneOffset.UTC);
    }
}
