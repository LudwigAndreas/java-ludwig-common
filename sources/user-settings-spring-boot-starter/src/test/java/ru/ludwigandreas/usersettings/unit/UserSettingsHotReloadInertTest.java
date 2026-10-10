package ru.ludwigandreas.usersettings.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.cache.api.LudwigCache;
import ru.ludwigandreas.hotreload.binding.HotReloadTypedConfigFactory;
import ru.ludwigandreas.usersettings.api.ResolvedSettings;
import ru.ludwigandreas.usersettings.api.SettingsSubject;
import ru.ludwigandreas.usersettings.config.UserSettingsHotReloadAutoConfiguration;

/**
 * On the classpath with nothing enabled, this module must not be what stops an application starting.
 *
 * <p>A service can carry the starter before it switches a mode on - notification-service's build
 * classpath does. The hot-reload auto-configuration used to be conditional on the hot-reload factory
 * alone, and one of its beans needs the settings cache, which only exists once a mode is enabled. So
 * a service with hot-reload on and user-settings merely present failed to start, on a missing cache
 * for a feature it was not using.
 */
class UserSettingsHotReloadInertTest {

    private static final String RELOAD_LISTENER = "settingsDefaultsReloadListener";
    private static final String DEFAULTS_PROVIDER = "settingDefaultsProvider";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(UserSettingsHotReloadAutoConfiguration.class))
            .withUserConfiguration(HotReloadPresent.class);

    @Test
    @DisplayName("with hot-reload on and no mode enabled the context starts and the module adds nothing")
    void inertWithNoModeEnabled() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(RELOAD_LISTENER);
            assertThat(context).doesNotHaveBean(DEFAULTS_PROVIDER);
        });
    }

    @Test
    @DisplayName("with the module switched off the context starts, even with a mode named")
    void inertWhenSwitchedOff() {
        runner.withPropertyValues(
                        "ludwig.user-settings.enabled=false",
                        "ludwig.user-settings.projection.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(RELOAD_LISTENER);
                });
    }

    @Test
    @DisplayName("with a mode enabled the reload listener and the defaults provider are registered")
    void activeWithAModeEnabled() {
        runner.withUserConfiguration(CachePresent.class)
                .withPropertyValues("ludwig.user-settings.projection.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean(RELOAD_LISTENER);
                    assertThat(context).hasBean(DEFAULTS_PROVIDER);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class HotReloadPresent {

        @Bean
        HotReloadTypedConfigFactory hotReloadTypedConfigFactory() {
            return mock(HotReloadTypedConfigFactory.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        }
    }

    /** Stands in for the cache {@code UserSettingsAutoConfiguration} creates once a mode is enabled. */
    @Configuration(proxyBeanMethods = false)
    static class CachePresent {

        @Bean
        @SuppressWarnings("unchecked")
        LudwigCache<SettingsSubject, ResolvedSettings> settingsCache() {
            return mock(LudwigCache.class);
        }
    }
}
