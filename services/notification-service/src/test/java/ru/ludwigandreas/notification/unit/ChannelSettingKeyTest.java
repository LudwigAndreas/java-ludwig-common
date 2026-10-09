package ru.ludwigandreas.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.preference.usersettings.NotificationSettings;

/**
 * That every channel maps to an opt-out setting key the settings module will accept.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The opt-out keys are derived from the channel's enum name, and a setting key is
 * {@code ^[a-z][a-z0-9]*([.\-][a-z0-9]+)*$} - lowercase segments separated by a dot or a hyphen, with
 * <b>no underscore</b>. Lowercasing the name was sufficient while every channel was a single word;
 * {@code IN_APP} is the first that is not, and {@code user.notifications.opt-out.all.in_app} is
 * rejected.
 *
 * <p>The failure mode is the reason this is a unit test rather than a comment. A setting definition
 * validates its key in its constructor, and the definitions are declared when the registry bean is
 * built - so an invalid key does not make one channel's opt-out unresolvable, it makes the entire
 * application fail to start, several beans deep, with a message naming a key rather than a channel.
 * That is exactly where it was found while implementing this change, after thirteen integration tests
 * failed to load a context. Here it is one assertion naming the constant.
 *
 * <p>This belongs to the settings adapter and not to {@code ChannelType}: the constraint comes from
 * the settings module's key format, and the enum has no business knowing about it.
 */
class ChannelSettingKeyTest {

    /** Mirrors {@code SettingDefinition.KEY_PATTERN}; a copy on purpose - see the method comment. */
    private static final String KEY_PATTERN = "^[a-z][a-z0-9]*([.\\-][a-z0-9]+)*$";

    /**
     * Asserted against a copy of the pattern as well as against the real constructor. The
     * constructor is the authority and is checked below; the pattern here is what makes a failure
     * legible, because it names the key shape instead of only rejecting one string.
     */
    @ParameterizedTest
    @EnumSource(ChannelType.class)
    @DisplayName("every channel's blanket opt-out key is a valid setting key")
    void blanketOptOutKeyIsValid(ChannelType channel) {
        String key = NotificationSettings.blanketOptOut(channel).getKey();

        assertThat(key).matches(KEY_PATTERN);
        assertThat(key).doesNotContain("_");
    }

    @ParameterizedTest
    @EnumSource(ChannelType.class)
    @DisplayName("every channel's per-category opt-out key is a valid setting key")
    void categoryOptOutKeyIsValid(ChannelType channel) {
        String key = NotificationSettings.optOut("order-updates", channel).getKey();

        assertThat(key).matches(KEY_PATTERN);
        assertThat(key).doesNotContain("_");
    }

    /**
     * The multi-word constant spelled out, so that the mapping is pinned rather than merely valid.
     * A transform that produced {@code inapp} would also pass the pattern and would silently be a
     * different published key.
     */
    @Test
    @DisplayName("a multi-word channel becomes a hyphenated key segment")
    void multiWordChannelIsHyphenated() {
        assertThat(NotificationSettings.blanketOptOut(ChannelType.IN_APP).getKey())
                .endsWith(".in-app");
    }

    /**
     * The real check, through the module's own validation: building the whole definition source is
     * what the registry bean does at startup, and it throws on the first invalid key.
     */
    @Test
    @DisplayName("the full definition source builds, which is what startup does")
    void definitionSourceBuildsForEveryChannel() {
        assertThatCode(() -> NotificationSettings.source(List.of("campaigns", "marketing")))
                .doesNotThrowAnyException();
    }
}
