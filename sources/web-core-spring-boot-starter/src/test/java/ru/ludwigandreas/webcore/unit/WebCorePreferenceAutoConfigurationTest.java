package ru.ludwigandreas.webcore.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;
import ru.ludwigandreas.webcore.config.WebCoreLocalizationAutoConfiguration;
import ru.ludwigandreas.webcore.config.WebCorePreferenceAutoConfiguration;
import ru.ludwigandreas.webcore.preference.RequestHeaderPreferenceSource;
import ru.ludwigandreas.webcore.preference.UserPreferenceDefaults;
import ru.ludwigandreas.webcore.preference.UserPreferenceFormatter;
import ru.ludwigandreas.webcore.preference.UserPreferenceLocaleContextResolver;
import ru.ludwigandreas.webcore.preference.UserPreferenceResolver;
import ru.ludwigandreas.webcore.preference.UserPreferenceSource;
import ru.ludwigandreas.webcore.preference.UserPreferences;

/**
 * The wiring: that this configuration takes the {@code localeResolver} name, that switching it off
 * gives the old resolver back, and that a module's contributed source joins the chain.
 */
class WebCorePreferenceAutoConfigurationTest {

    private final WebApplicationContextRunner webRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    WebCorePreferenceAutoConfiguration.class,
                    WebCoreLocalizationAutoConfiguration.class));

    private final ApplicationContextRunner plainRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    WebCorePreferenceAutoConfiguration.class,
                    WebCoreLocalizationAutoConfiguration.class));

    @AfterEach
    void resetStaticState() {
        UserPreferenceDefaults.reset();
    }

    @Test
    @DisplayName("the localeResolver is the preference-aware one, and the AcceptHeader one backs off")
    void theLocaleResolverIsReplaced() {
        webRunner.run(context -> {
            assertThat(context).hasSingleBean(LocaleResolver.class);
            assertThat(context.getBean("localeResolver"))
                    .isInstanceOf(UserPreferenceLocaleContextResolver.class);
        });
    }

    @Test
    @DisplayName("switched off, the AcceptHeaderLocaleResolver comes back with no further wiring")
    void disablingRestoresThePreviousBehaviour() {
        webRunner.withPropertyValues("ludwig.web.preferences.enabled=false").run(context -> {
            assertThat(context.getBean("localeResolver")).isInstanceOf(AcceptHeaderLocaleResolver.class);
            assertThat(context).doesNotHaveBean(UserPreferenceResolver.class);
            assertThat(context).doesNotHaveBean(UserPreferenceFormatter.class);
        });
    }

    @Test
    @DisplayName("the whole starter's master switch turns this off too")
    void theStarterSwitchAppliesHere() {
        webRunner.withPropertyValues("ludwig.web.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(UserPreferenceResolver.class));
    }

    @Test
    @DisplayName("outside a servlet application the contract still works, with configuration as its only source")
    void worksWithoutServletBeans() {
        plainRunner.run(context -> {
            assertThat(context).hasSingleBean(UserPreferenceResolver.class);
            assertThat(context).hasSingleBean(UserPreferenceFormatter.class);
            // The two servlet-dependent beans are the ones that must not appear: a batch module that
            // formats a date must not acquire Spring MVC through this starter.
            assertThat(context).doesNotHaveBean(RequestHeaderPreferenceSource.class);
            assertThat(context).doesNotHaveBean(UserPreferenceLocaleContextResolver.class);
        });
    }

    @Test
    @DisplayName("the configured defaults are installed process-wide, so an off-request thread agrees")
    void theConfiguredDefaultsAreInstalled() {
        webRunner.withPropertyValues(
                        "ludwig.web.i18n.default-locale=ru",
                        "ludwig.web.i18n.supported-locales=en,ru",
                        "ludwig.web.preferences.default-zone=Asia/Yekaterinburg")
                .run(context -> assertThat(UserPreferences.current()).isEqualTo(
                        new UserPreferences(Locale.forLanguageTag("ru"), ZoneId.of("Asia/Yekaterinburg"))));
    }

    @Test
    @DisplayName("a module's contributed source joins the chain, which is how user-settings reaches it")
    void aContributedSourceJoinsTheChain() {
        webRunner.withUserConfiguration(ContributedSourceConfiguration.class).run(context -> {
            UserPreferenceResolver resolver = context.getBean(UserPreferenceResolver.class);

            assertThat(resolver.sourceNames()).contains("a contributed source");
            assertThat(resolver.resolve().zone()).isEqualTo(ZoneId.of("Asia/Yekaterinburg"));
        });
    }

    @Test
    @DisplayName("every bean yields to an application's own")
    void applicationBeansWin() {
        webRunner.withUserConfiguration(OverridingConfiguration.class)
                .run(context -> assertThat(context.getBean("localeResolver"))
                        .isInstanceOf(AcceptHeaderLocaleResolver.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class ContributedSourceConfiguration {

        @Bean
        UserPreferenceSource contributed() {
            return new UserPreferenceSource() {
                @Override
                public Optional<Locale> locale() {
                    return Optional.empty();
                }

                @Override
                public Optional<ZoneId> zone() {
                    return Optional.of(ZoneId.of("Asia/Yekaterinburg"));
                }

                @Override
                public int getOrder() {
                    return STORED_ORDER;
                }

                @Override
                public String sourceName() {
                    return "a contributed source";
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OverridingConfiguration {

        @Bean
        LocaleResolver localeResolver() {
            return new AcceptHeaderLocaleResolver();
        }
    }
}
