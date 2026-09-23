package ru.ludwigandreas.usersettings.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import ru.ludwigandreas.hotreload.binding.HotReloadTypedConfigFactory;
import ru.ludwigandreas.hotreload.binding.RefreshableConfig;
import ru.ludwigandreas.hotreload.event.ConfigurationRefreshedEvent;
import ru.ludwigandreas.usersettings.cache.SettingsCache;
import ru.ludwigandreas.usersettings.resolve.PropertiesSettingDefaultsProvider;
import ru.ludwigandreas.usersettings.resolve.SettingDefaultsProvider;

/**
 * Makes the tenant and platform default layers reloadable without a redeploy.
 *
 * <p>Changing a default is a routine operational act - "new users should get the weekly digest from
 * now on" - and without this it is a release. With the hot-reload module present, the defaults are
 * read through a {@link RefreshableConfig} that rebinds when its source file or Vault path changes,
 * and the settings cache is cleared so the new default is visible immediately rather than at the end
 * of the TTL.
 *
 * <p>Ordered <em>before</em> the core configuration, which registers the static provider under
 * {@code @ConditionalOnMissingBean}: written the other way round the static one would always win and
 * a reload would update a copy nobody reads.
 *
 * <p>Note what is <em>not</em> reloadable: the definitions themselves. A definition is a
 * compile-time artifact, and a reload that could add or retype one would be the runtime schema this
 * module was built to avoid.
 */
@Slf4j
@AutoConfiguration(before = UserSettingsAutoConfiguration.class)
@ConditionalOnClass(HotReloadTypedConfigFactory.class)
@ConditionalOnBean(HotReloadTypedConfigFactory.class)
public class UserSettingsHotReloadAutoConfiguration {

    /** The configuration prefix the defaults are bound from; the same one the properties bean uses. */
    public static final String PREFIX = "ludwig.user-settings";

    /**
     * Reads the defaults through a live view.
     *
     * <p>The supplier is invoked on every resolution rather than captured once, which is what makes a
     * reload take effect at all - see {@link PropertiesSettingDefaultsProvider}.
     */
    @Bean
    @ConditionalOnMissingBean
    public SettingDefaultsProvider settingDefaultsProvider(HotReloadTypedConfigFactory factory) {
        RefreshableConfig<UserSettingsProperties> config =
                factory.create(PREFIX, UserSettingsProperties.class);
        return new PropertiesSettingDefaultsProvider(
                () -> config.get().getPlatformDefaults(),
                () -> config.get().getTenantDefaults());
    }

    @Bean
    @ConditionalOnMissingBean
    public SettingsDefaultsReloadListener settingsDefaultsReloadListener(SettingsCache cache) {
        return new SettingsDefaultsReloadListener(cache);
    }

    /**
     * Clears the whole cache when any hot-reloadable source changes.
     *
     * <p>Everything, not just the entries that inherit a changed default, because working out which
     * subjects those are would need a query per changed key against the directory - and a reload is
     * rare enough that rebuilding a few thousand cache entries costs less than the bookkeeping to
     * avoid it. Listening for every source rather than only the ones carrying settings keys is the
     * same trade: a source that turns out to carry no settings costs one unnecessary flush.
     */
    @RequiredArgsConstructor
    public static class SettingsDefaultsReloadListener {

        private final SettingsCache cache;

        /** Clears the cache so a reloaded default is visible now rather than at the end of the TTL. */
        @EventListener
        public void onConfigurationRefreshed(ConfigurationRefreshedEvent event) {
            log.info("Configuration source {} reloaded; clearing the settings cache so any changed"
                    + " tenant or platform default takes effect now rather than at the end of the TTL",
                    event.getSourceId());
            cache.evictAll();
        }
    }
}
