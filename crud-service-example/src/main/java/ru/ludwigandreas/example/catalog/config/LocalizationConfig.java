package ru.ludwigandreas.example.catalog.config;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

/**
 * Localization for the whole service: one bundle, one locale source, one validator.
 *
 * <p>The locale comes from the request's {@code Accept-Language} header, so a stateless API needs
 * no session or cookie to answer in the caller's language, and anything unsupported falls back to
 * English rather than to whatever locale the server host happens to run in.
 */
@Configuration(proxyBeanMethods = false)
public class LocalizationConfig {

    private static final List<Locale> SUPPORTED_LOCALES =
            List.of(Locale.ENGLISH, Locale.forLanguageTag("ru"));

    @Bean
    public MessageSource messageSource() {
        ReloadableResourceBundleMessageSource messageSource = new ReloadableResourceBundleMessageSource();
        messageSource.setBasename("classpath:i18n/messages");
        messageSource.setDefaultEncoding(StandardCharsets.UTF_8.name());
        // Without this, a locale with no bundle silently picks up the server's own locale - which
        // makes the API's language depend on how the container happens to be configured.
        messageSource.setFallbackToSystemLocale(false);
        messageSource.setUseCodeAsDefaultMessage(false);
        return messageSource;
    }

    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver localeResolver = new AcceptHeaderLocaleResolver();
        localeResolver.setSupportedLocales(SUPPORTED_LOCALES);
        localeResolver.setDefaultLocale(Locale.ENGLISH);
        return localeResolver;
    }

    /**
     * Declaring this bean makes Bean Validation resolve {@code {key}} constraint messages against
     * the bundle above instead of Hibernate Validator's built-in English defaults, and Spring Boot
     * back off from its own default validator.
     */
    @Bean
    public LocalValidatorFactoryBean validator(MessageSource messageSource) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.setValidationMessageSource(messageSource);
        return validator;
    }
}
