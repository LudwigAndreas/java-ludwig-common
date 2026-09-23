package ru.ludwigandreas.notification.config;

import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Matches when this deployment is willing to read preferences from the user-settings module.
 *
 * <p>Two of the three values of {@code ludwig.notification.preferences.source} allow it - {@code AUTO}
 * and {@code USER_SETTINGS} - and {@code @ConditionalOnProperty} can express one value or a default
 * but not "either of these two". {@link AnyNestedCondition} is Spring's supported way to say it; the
 * user-settings module itself uses the same shape to gate its two modes, so the idiom is already in
 * this codebase.
 *
 * <p>{@code AUTO} is the default and matches when the property is absent, which is what makes adding
 * the module to the classpath sufficient to switch a deployment over. {@code NONE} does not match,
 * and is how a deployment that has the module for some other reason keeps preference resolution on
 * configured defaults anyway.
 *
 * <p>Note what this condition does <em>not</em> do: it does not check that the module is present or
 * enabled. Presence is a {@code @ConditionalOnClass} on the configuration this guards, and whether
 * the module actually produced a {@code SettingsLookup} is decided at bean-creation time, where the
 * answer is knowable. See {@link UserSettingsPreferenceConfig}.
 */
public class PreferenceSourceCondition extends AnyNestedCondition {

    public PreferenceSourceCondition() {
        super(ConfigurationPhase.REGISTER_BEAN);
    }

    @ConditionalOnProperty(prefix = "ludwig.notification.preferences", name = "source",
            havingValue = "AUTO", matchIfMissing = true)
    static class Auto {
    }

    @ConditionalOnProperty(prefix = "ludwig.notification.preferences", name = "source",
            havingValue = "USER_SETTINGS")
    static class Required {
    }
}
