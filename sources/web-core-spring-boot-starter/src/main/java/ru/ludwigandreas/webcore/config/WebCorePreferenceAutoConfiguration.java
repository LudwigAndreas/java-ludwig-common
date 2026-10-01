package ru.ludwigandreas.webcore.config;

import jakarta.servlet.http.HttpServletRequest;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleContextResolver;
import ru.ludwigandreas.webcore.preference.ConfiguredPreferenceSource;
import ru.ludwigandreas.webcore.preference.RequestHeaderPreferenceSource;
import ru.ludwigandreas.webcore.preference.UserPreferenceFormatter;
import ru.ludwigandreas.webcore.preference.UserPreferenceLocaleContextResolver;
import ru.ludwigandreas.webcore.preference.UserPreferenceResolver;
import ru.ludwigandreas.webcore.preference.UserPreferenceSource;
import ru.ludwigandreas.webcore.preference.UserPreferenceStartup;
import ru.ludwigandreas.webcore.preference.UserPreferences;

/**
 * The caller-preference contract: the source chain, the formatter and the {@code localeResolver}
 * that publishes a timezone-aware context.
 *
 * <h2>How this replaces the AcceptHeaderLocaleResolver without editing it</h2>
 *
 * <p>Declared {@code before} {@link WebCoreLocalizationAutoConfiguration}, whose
 * {@code localeResolver} bean is {@code @ConditionalOnMissingBean(name = "localeResolver")}. So this
 * one wins by ordering, exactly as that class wins over Spring Boot's own by the same mechanism, and
 * nothing in it has to change.
 *
 * <p>It also means {@code ludwig.web.preferences.enabled=false} needs no other wiring: this
 * configuration backs off, the {@code AcceptHeaderLocaleResolver} is registered exactly as it was
 * before this contract existed, and a behaviour that reaches every response in the platform is
 * revertible with one property rather than with a rollback.
 *
 * <h2>What is conditional on what, and why</h2>
 *
 * <p>The resolver, the formatter and the configured source need nothing but
 * {@code spring-core}: a batch module or a Kafka consumer that formats a date for a template gets
 * the whole contract, with the configured defaults as the only source, and
 * {@code UserPreferences.bind()} to carry a captured caller's preferences onto its worker. Only the
 * two servlet-dependent beans are nested behind {@code @ConditionalOnWebApplication}, for the same
 * reason {@code LocaleResolverConfiguration} already is in the localization configuration: this
 * module's {@code spring-webmvc} and {@code jakarta.servlet-api} dependencies are both optional, and
 * a return type is introspected whether or not the bean is created.
 */
@AutoConfiguration(before = WebCoreLocalizationAutoConfiguration.class)
@ConditionalOnProperty(
        name = {"ludwig.web.enabled", "ludwig.web.preferences.enabled"},
        matchIfMissing = true)
@EnableConfigurationProperties(WebCoreProperties.class)
public class WebCorePreferenceAutoConfiguration {

    /**
     * The deployment's configured preferences, as one value both the configured source and the
     * process-wide defaults are built from.
     *
     * <p>One bean rather than two reads of the properties, so that the source the chain ends with
     * and the answer {@link UserPreferences#current()} gives off a request thread cannot disagree.
     * Those two disagreeing would be invisible in a request and wrong in every scheduled job.
     *
     * <p>The default locale is <em>not</em> a property of its own here: it is
     * {@code ludwig.web.i18n.default-locale}, which already exists and already drives the
     * {@code MessageSource}. Two properties naming the same thing is how they come to disagree.
     */
    @Bean
    @ConditionalOnMissingBean(name = "ludwigConfiguredPreferences")
    public UserPreferences ludwigConfiguredPreferences(WebCoreProperties properties) {
        Locale locale = Locale.forLanguageTag(properties.getI18n().getDefaultLocale());
        ZoneId zone = ZoneId.of(properties.getPreferences().getDefaultZone());
        return new UserPreferences(locale, zone);
    }

    /** The lowest-priority source, which is what makes resolution total. */
    @Bean
    @ConditionalOnMissingBean(ConfiguredPreferenceSource.class)
    public ConfiguredPreferenceSource ludwigConfiguredPreferenceSource(UserPreferences ludwigConfiguredPreferences) {
        return new ConfiguredPreferenceSource(ludwigConfiguredPreferences);
    }

    /**
     * The chain.
     *
     * <p>Takes every {@link UserPreferenceSource} in the context, including ones contributed by
     * other modules - which is how {@code user-settings-spring-boot-starter}'s stored source joins
     * without {@code web-core} knowing it exists, and without the dependency that would require.
     */
    @Bean
    @ConditionalOnMissingBean(UserPreferenceResolver.class)
    public UserPreferenceResolver ludwigUserPreferenceResolver(List<UserPreferenceSource> sources,
                                                               WebCoreProperties properties) {
        List<Locale> supported = properties.getI18n().getSupportedLocales().stream()
                .map(Locale::forLanguageTag)
                .toList();
        return new UserPreferenceResolver(sources, supported);
    }

    /** The conversions a mapper names in {@code @Mapper(uses = ...)}. */
    @Bean
    @ConditionalOnMissingBean(UserPreferenceFormatter.class)
    public UserPreferenceFormatter ludwigUserPreferenceFormatter() {
        return new UserPreferenceFormatter();
    }

    /** Installs the process-wide defaults and logs the active sources; see its javadoc. */
    @Bean
    public UserPreferenceStartup ludwigUserPreferenceStartup(UserPreferences ludwigConfiguredPreferences,
                                                            UserPreferenceResolver resolver) {
        return new UserPreferenceStartup(ludwigConfiguredPreferences, resolver);
    }

    /** The servlet-only source, in its own class so the servlet types are introspected nowhere else. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(HttpServletRequest.class)
    public static class RequestHeaderSourceConfiguration {

        /** {@code Accept-Language} and the configured timezone header. */
        @Bean
        @ConditionalOnMissingBean(RequestHeaderPreferenceSource.class)
        public RequestHeaderPreferenceSource ludwigRequestHeaderPreferenceSource(WebCoreProperties properties) {
            WebCoreProperties.Preferences preferences = properties.getPreferences();
            return new RequestHeaderPreferenceSource(
                    preferences.getTimeZoneHeader(), preferences.isAcceptLanguage());
        }
    }

    /** The {@code localeResolver} replacement, nested for the same reason. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(LocaleContextResolver.class)
    public static class PreferenceLocaleResolverConfiguration {

        /**
         * Named {@code localeResolver} because that is the name {@code DispatcherServlet} looks up.
         *
         * <p>The name is the mechanism, not a convention: the servlet finds a
         * {@code LocaleContextResolver} under it and publishes the context this returns, so
         * {@code LocaleContextHolder.getTimeZone()} starts answering the caller's zone for every
         * existing caller in the platform with no code change.
         */
        @Bean
        @ConditionalOnMissingBean(name = "localeResolver")
        public LocaleContextResolver localeResolver(UserPreferenceResolver resolver) {
            return new UserPreferenceLocaleContextResolver(resolver);
        }
    }
}
