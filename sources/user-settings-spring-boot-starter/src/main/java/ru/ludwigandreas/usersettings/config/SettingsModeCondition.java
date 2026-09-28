package ru.ludwigandreas.usersettings.config;

import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Matches when the service has said which mode it runs in.
 *
 * <p>The shared half of the module - the registry, the converters, the cache, resolution - is needed
 * by both modes and by neither on its own, so it cannot be gated on a single property. An
 * {@link AnyNestedCondition} is Spring's supported way to express the "either of these" that
 * {@code @ConditionalOnProperty} cannot.
 *
 * <p>Both being off is the default, and means the module contributes nothing. That is deliberate:
 * adding the dependency must not start projecting a topic or creating tables until somebody says so.
 */
public class SettingsModeCondition extends AnyNestedCondition {

    public SettingsModeCondition() {
        super(ConfigurationPhase.REGISTER_BEAN);
    }

    @ConditionalOnProperty(prefix = "ludwig.user-settings.owner", name = "enabled", havingValue = "true")
    static class OwnerMode {
    }

    @ConditionalOnProperty(prefix = "ludwig.user-settings.projection", name = "enabled", havingValue = "true")
    static class ProjectionMode {
    }
}
