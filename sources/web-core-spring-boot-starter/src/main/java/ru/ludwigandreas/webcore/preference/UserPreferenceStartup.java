package ru.ludwigandreas.webcore.preference;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;

/**
 * Installs the deployment's configured defaults and logs which sources are active.
 *
 * <h2>Why the log line is not noise</h2>
 *
 * <p>It is the mitigation for the one failure in this contract that no check can catch. The stored
 * preference source is registered only when the service has actually declared
 * {@code WellKnownSettings.LOCALE} and {@code TIMEZONE} as a {@code SettingDefinitionSource}, which
 * is what keeps that module's opt-in promise - a service resolves exactly the settings it declares.
 * The consequence is that a service which <em>wanted</em> stored preferences and forgot the bean
 * behaves identically to one that never wanted them: headers and configuration, no error, no
 * warning, and a user whose saved timezone is quietly ignored.
 *
 * <p>Neither ArchUnit nor Checkstyle can tell those two services apart - the difference is a bean
 * that is absent, and an absent bean is indistinguishable from a deliberate choice. So the active
 * sources are named once, at startup, at INFO, where the gap is visible to whoever is wondering why
 * a saved preference has no effect.
 */
@Slf4j
public class UserPreferenceStartup implements InitializingBean {

    private final UserPreferences configured;

    private final UserPreferenceResolver resolver;

    /**
     * @param configured the deployment's defaults
     * @param resolver   the chain, asked for its source names
     */
    public UserPreferenceStartup(UserPreferences configured, UserPreferenceResolver resolver) {
        this.configured = configured;
        this.resolver = resolver;
    }

    @Override
    public void afterPropertiesSet() {
        UserPreferenceDefaults.install(configured);
        log.info("Caller preferences resolve from {}; defaults are locale={} zone={}",
                resolver.sourceNames(), configured.locale().toLanguageTag(), configured.zone());
    }
}
