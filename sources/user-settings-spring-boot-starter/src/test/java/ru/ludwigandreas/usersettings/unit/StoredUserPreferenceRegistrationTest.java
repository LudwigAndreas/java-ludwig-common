package ru.ludwigandreas.usersettings.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.usersettings.api.SettingDefinitionSource;
import ru.ludwigandreas.usersettings.api.SettingsLookup;
import ru.ludwigandreas.usersettings.config.UserSettingsPreferenceAutoConfiguration;
import ru.ludwigandreas.usersettings.convert.SettingValueConverterRegistry;
import ru.ludwigandreas.usersettings.preference.StoredUserPreferenceSource;
import ru.ludwigandreas.usersettings.registry.SettingDefinitionRegistry;
import ru.ludwigandreas.usersettings.wellknown.WellKnownSettings;
import ru.ludwigandreas.webcore.preference.UserPreferenceSource;

/**
 * How the stored source reaches {@code web-core}'s chain.
 *
 * <p>The claim being pinned is the unusual one: the source is registered whenever there is a lookup,
 * including in a service that declared none of the well-known settings, and abstains out loud instead
 * of being conditionally absent. A silently missing bean would make "my saved timezone does nothing"
 * a question with no visible answer.
 */
class StoredUserPreferenceRegistrationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(UserSettingsPreferenceAutoConfiguration.class));

    @Test
    @DisplayName("with a lookup present, the source joins the chain as a UserPreferenceSource")
    void theSourceIsContributed() {
        runner.withUserConfiguration(DeclaredConfiguration.class).run(context -> {
            assertThat(context).hasSingleBean(StoredUserPreferenceSource.class);
            assertThat(context.getBeansOfType(UserPreferenceSource.class)).hasSize(1);
            assertThat(context.getBean(StoredUserPreferenceSource.class).sourceName())
                    .contains("user.locale", "user.timezone");
        });
    }

    @Test
    @DisplayName("with no lookup there is nothing to read, and no bean")
    void noLookupMeansNoSource() {
        runner.run(context -> assertThat(context).doesNotHaveBean(StoredUserPreferenceSource.class));
    }

    @Test
    @DisplayName("a service that declared neither setting still gets the bean, abstaining out loud")
    void anUndeclaringServiceStillGetsTheBean() {
        runner.withUserConfiguration(UndeclaredConfiguration.class).run(context -> {
            StoredUserPreferenceSource source = context.getBean(StoredUserPreferenceSource.class);

            assertThat(source.sourceName()).contains("inactive");
            assertThat(source.locale()).isEmpty();
            assertThat(source.zone()).isEmpty();
        });
    }

    @Test
    @DisplayName("the contribution can be switched off without switching off the module")
    void theContributionCanBeDisabled() {
        runner.withUserConfiguration(DeclaredConfiguration.class)
                .withPropertyValues("ludwig.user-settings.preferences.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(StoredUserPreferenceSource.class));
    }

    @Test
    @DisplayName("an application's own source wins")
    void anApplicationSourceWins() {
        runner.withUserConfiguration(DeclaredConfiguration.class, OverridingConfiguration.class)
                .run(context -> assertThat(context.getBean(StoredUserPreferenceSource.class))
                        .isSameAs(context.getBean("mySource")));
    }

    @Configuration(proxyBeanMethods = false)
    static class DeclaredConfiguration {

        @Bean
        SettingsLookup settingsLookup() {
            return mock(SettingsLookup.class);
        }

        @Bean
        SettingDefinitionRegistry settingDefinitionRegistry() {
            return registryFor(SettingDefinitionSource.of(
                    WellKnownSettings.LOCALE, WellKnownSettings.TIMEZONE));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UndeclaredConfiguration {

        @Bean
        SettingsLookup settingsLookup() {
            return mock(SettingsLookup.class);
        }

        @Bean
        SettingDefinitionRegistry settingDefinitionRegistry() {
            return registryFor(SettingDefinitionSource.of(TestSettings.PAGE_SIZE));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OverridingConfiguration {

        @Bean
        StoredUserPreferenceSource mySource(SettingsLookup lookup, SettingDefinitionRegistry registry) {
            return new StoredUserPreferenceSource(lookup, registry);
        }
    }

    private static SettingDefinitionRegistry registryFor(SettingDefinitionSource source) {
        return new SettingDefinitionRegistry(List.of(source),
                new SettingValueConverterRegistry(List.of(), new ObjectMapper().findAndRegisterModules()));
    }
}
