package ru.ludwigandreas.webcore.config;

import java.util.Locale;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.validation.Validator;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

/**
 * Localization for the whole service: one bundle, one locale source, one validator.
 *
 * <p>These three beans are the other half of what every REST service was copying, and they are what
 * make the problem pipeline actually produce translated text rather than just being able to. Without
 * the locale resolver there is no request locale to resolve against; without the validator wiring, a
 * rejected request comes back with a translated {@code title} and English field messages, because
 * Hibernate Validator interpolates its own defaults unless it is pointed at the application's
 * {@code MessageSource}.
 *
 * <p>The locale comes from the request's {@code Accept-Language} header, so a stateless API needs no
 * session or cookie to answer in the caller's language, and anything unsupported falls back to the
 * configured default rather than to whatever locale the server host happens to run in.
 *
 * <h2>Why this overrides Spring Boot's own beans</h2>
 *
 * <p>Declared {@code before} Boot's {@link MessageSourceAutoConfiguration} and
 * {@link ValidationAutoConfiguration}, both of which back off when a bean of the relevant type is
 * already defined - so this wins by ordering rather than by fighting. Two escape hatches: any
 * application bean of the same type takes precedence over this (every bean here is
 * {@code @ConditionalOnMissingBean}), and {@code ludwig.web.i18n.enabled=false} hands the whole
 * question back to Boot's defaults.
 */
@AutoConfiguration(before = {MessageSourceAutoConfiguration.class, ValidationAutoConfiguration.class})
@ConditionalOnProperty(
        name = {"ludwig.web.enabled", "ludwig.web.i18n.enabled"},
        matchIfMissing = true)
@EnableConfigurationProperties(WebCoreProperties.class)
public class WebCoreLocalizationAutoConfiguration {

    /**
     * The application's own bundles.
     *
     * <p>Named {@code messageSource} because that is the name the container looks up for message
     * resolution, and the name Boot's own autoconfiguration backs off from.
     *
     * <p>Reloadable rather than a plain {@code ResourceBundleMessageSource}: with a positive
     * {@code cache-duration} a changed properties file is picked up without a restart, which is the
     * difference between a translation pass taking an afternoon and taking a week. The default still
     * caches forever, so production pays nothing for it.
     */
    @Bean
    @ConditionalOnMissingBean(name = "messageSource")
    public MessageSource messageSource(WebCoreProperties properties) {
        WebCoreProperties.I18n i18n = properties.getI18n();
        ReloadableResourceBundleMessageSource messageSource = new ReloadableResourceBundleMessageSource();
        messageSource.setBasenames(i18n.getBasenames().toArray(String[]::new));
        messageSource.setDefaultEncoding(i18n.getEncoding());
        // Without this, a locale with no bundle silently picks up the server's own locale - which
        // makes the API's language depend on how the container happens to be configured.
        messageSource.setFallbackToSystemLocale(i18n.isFallbackToSystemLocale());
        // A missing key must fall through to the modules' contributed bundles; returning the key
        // itself here would stop that chain at its first link and publish the key as the message.
        messageSource.setUseCodeAsDefaultMessage(false);
        messageSource.setCacheMillis(i18n.getCacheDuration().toMillis());
        return messageSource;
    }

    /**
     * The request-locale resolver, in its own nested class so that its {@code LocaleResolver} return
     * type is only ever introspected in an application that actually has Spring MVC.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(AcceptHeaderLocaleResolver.class)
    public static class LocaleResolverConfiguration {

        /**
         * The request's locale, from {@code Accept-Language}.
         *
         * <p>{@code supportedLocales} is the load-bearing part: a caller asking for a language this
         * service has no bundle for gets the default in full, instead of a response assembled from
         * whichever keys happened to have a translation.
         *
         * <p>Header-based rather than cookie- or session-based deliberately. Both of those make the
         * response depend on state the caller did not send with this request, which a stateless API
         * behind a load balancer cannot rely on; a service that genuinely needs one declares its own
         * {@code localeResolver} and this backs off.
         */
        @Bean
        @ConditionalOnMissingBean(name = "localeResolver")
        public LocaleResolver localeResolver(WebCoreProperties properties) {
            WebCoreProperties.I18n i18n = properties.getI18n();
            AcceptHeaderLocaleResolver localeResolver = new AcceptHeaderLocaleResolver();
            localeResolver.setSupportedLocales(i18n.getSupportedLocales().stream()
                    .map(Locale::forLanguageTag)
                    .toList());
            localeResolver.setDefaultLocale(Locale.forLanguageTag(i18n.getDefaultLocale()));
            return localeResolver;
        }
    }

    /** Nested for the same reason: {@code LocalValidatorFactoryBean} needs Bean Validation present. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {"jakarta.validation.Validator", "jakarta.validation.ValidatorFactory"})
    @ConditionalOnProperty(prefix = "ludwig.web.i18n", name = "configure-validator", matchIfMissing = true)
    public static class ValidationMessageConfiguration {

        /**
         * Makes Bean Validation resolve its {@code {key}} constraint messages against the
         * application's {@code MessageSource} instead of Hibernate Validator's English defaults.
         *
         * <p>Named and typed to match Boot's own {@code defaultValidator}, so Boot's
         * {@code @ConditionalOnMissingBean(Validator.class)} sees this one and does not stand up a
         * second validator beside it.
         *
         * <p>{@code static} for the same reason Boot declares its own that way: a {@code Validator}
         * is consumed by bean post-processors, and a non-static factory method would force this
         * configuration class - and everything it injects - to be instantiated before
         * post-processing has been set up.
         */
        @Bean
        @ConditionalOnMissingBean(Validator.class)
        public static LocalValidatorFactoryBean defaultValidator(MessageSource messageSource) {
            LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
            validator.setValidationMessageSource(messageSource);
            return validator;
        }
    }
}
