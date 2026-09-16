package ru.ludwigandreas.webcore.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Everything the starter can be configured with, under {@code ludwig.web}.
 *
 * <p>The defaults are the shape a service should ship with: problems rendered for every failure,
 * localized against the request's {@code Accept-Language}, with a trace id but without any echo of
 * what the caller submitted. A service that configures nothing gets all of that.
 */
@ConfigurationProperties(prefix = "ludwig.web")
public class WebCoreProperties {

    /** Master switch for the whole starter's autoconfiguration. */
    private boolean enabled = true;

    @NestedConfigurationProperty
    private final Problem problem = new Problem();

    @NestedConfigurationProperty
    private final I18n i18n = new I18n();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Problem getProblem() {
        return problem;
    }

    public I18n getI18n() {
        return i18n;
    }

    /** RFC 9457 problem rendering: {@code ludwig.web.problem.*}. */
    public static class Problem {

        /**
         * Whether the shared {@code @RestControllerAdvice} is registered. Switching it off leaves
         * {@code LocalizedException}, {@code ProblemMessages} and the mappers usable - for a service
         * that renders problems from its own advice but still wants the localized pipeline.
         */
        private boolean enabled = true;

        /**
         * Prefix for the problem's {@code type} URI; the code is appended to it. Point it at your own
         * documentation - {@code https://errors.example.com/} - and the type becomes dereferenceable,
         * which is what RFC 9457 intends.
         */
        private String typePrefix = "urn:ludwig:problem:";

        /** Whether {@code instance} is set to the request URI. */
        private boolean includeInstance = true;

        /** Whether a {@code timestamp} member is added. */
        private boolean includeTimestamp = true;

        /** Whether the trace id is published in the body; see {@code TraceIdProvider}. */
        private boolean includeTraceId = true;

        /** MDC key the trace id is read from. Micrometer Tracing and the OTel agent both use this. */
        private String traceIdMdcKey = "traceId";

        /** Name of the problem member the trace id is published as. */
        private String traceIdProperty = "traceId";

        /**
         * Whether a rejected field's submitted value is echoed back in {@code violations}.
         *
         * <p>Off by default, and worth leaving off: the field that most often fails validation is
         * also the one most likely to hold a password, a token or a card number, and a problem body
         * is exactly the kind of thing that gets pasted into a ticket.
         */
        private boolean includeRejectedValue = false;

        /**
         * Whether the exception's own message is added as a {@code debug} member for 5xx problems.
         *
         * <p>Off by default. It is genuinely useful in a development environment and it is an
         * information leak anywhere else, which is why it is a switch and not a profile check: the
         * decision belongs in the deployment's configuration, where it can be audited.
         */
        private boolean includeExceptionMessage = false;

        /**
         * Order of the shared advice. Last by default, and that is load-bearing.
         *
         * <p>The advice handles {@code Exception}. Spring returns the first advice that can handle a
         * thrown exception <em>at all</em>, not the one with the most specific handler for it - so an
         * advice sitting anywhere but last would silently swallow every exception a service's own
         * {@code @RestControllerAdvice} was written to render, including specific types it declares
         * explicitly. Last means every other advice gets first refusal, which is the only correct
         * position for a fallback.
         *
         * <p>Ties are resolved in the application's favour: Spring's sort is stable, and
         * autoconfiguration beans are registered after the application's own, so an
         * {@code @RestControllerAdvice} with no {@code @Order} still precedes this one. Giving it an
         * explicit {@code @Order} makes that independent of registration order.
         */
        private int adviceOrder = Ordered.LOWEST_PRECEDENCE;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTypePrefix() {
            return typePrefix;
        }

        public void setTypePrefix(String typePrefix) {
            this.typePrefix = typePrefix;
        }

        public boolean isIncludeInstance() {
            return includeInstance;
        }

        public void setIncludeInstance(boolean includeInstance) {
            this.includeInstance = includeInstance;
        }

        public boolean isIncludeTimestamp() {
            return includeTimestamp;
        }

        public void setIncludeTimestamp(boolean includeTimestamp) {
            this.includeTimestamp = includeTimestamp;
        }

        public boolean isIncludeTraceId() {
            return includeTraceId;
        }

        public void setIncludeTraceId(boolean includeTraceId) {
            this.includeTraceId = includeTraceId;
        }

        public String getTraceIdMdcKey() {
            return traceIdMdcKey;
        }

        public void setTraceIdMdcKey(String traceIdMdcKey) {
            this.traceIdMdcKey = traceIdMdcKey;
        }

        public String getTraceIdProperty() {
            return traceIdProperty;
        }

        public void setTraceIdProperty(String traceIdProperty) {
            this.traceIdProperty = traceIdProperty;
        }

        public boolean isIncludeRejectedValue() {
            return includeRejectedValue;
        }

        public void setIncludeRejectedValue(boolean includeRejectedValue) {
            this.includeRejectedValue = includeRejectedValue;
        }

        public boolean isIncludeExceptionMessage() {
            return includeExceptionMessage;
        }

        public void setIncludeExceptionMessage(boolean includeExceptionMessage) {
            this.includeExceptionMessage = includeExceptionMessage;
        }

        public int getAdviceOrder() {
            return adviceOrder;
        }

        public void setAdviceOrder(int adviceOrder) {
            this.adviceOrder = adviceOrder;
        }
    }

    /** Request-locale resolution and the application's own bundles: {@code ludwig.web.i18n.*}. */
    public static class I18n {

        /**
         * Whether the starter configures the {@code MessageSource}, the {@code LocaleResolver} and
         * the validator. Switch it off to keep Spring Boot's own defaults and wire them yourself;
         * the problem pipeline still works, it simply resolves text against whatever
         * {@code MessageSource} the application ended up with.
         */
        private boolean enabled = true;

        /** The application's own bundles. Module bundles are contributed as beans, not listed here. */
        private List<String> basenames = new ArrayList<>(List.of("classpath:i18n/messages"));

        private String encoding = "UTF-8";

        /**
         * Locales the API answers in. A request asking for anything else gets
         * {@link #defaultLocale}, rather than a half-translated response.
         */
        private List<String> supportedLocales = new ArrayList<>(List.of("en"));

        private String defaultLocale = "en";

        /**
         * Whether an unresolved key may fall back to the server's own locale. Off, because that makes
         * the API's language depend on how the container happens to be configured.
         */
        private boolean fallbackToSystemLocale = false;

        /**
         * How long a loaded bundle is cached. {@code -1} (the default) caches forever; a positive
         * value re-reads changed files, which is useful while translating and pointless in production.
         */
        private Duration cacheDuration = Duration.ofSeconds(-1);

        /**
         * Whether Bean Validation resolves its {@code {key}} constraint messages against the
         * {@code MessageSource} above instead of Hibernate Validator's built-in English defaults.
         * This is what makes a {@code violations} entry localized.
         */
        private boolean configureValidator = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getBasenames() {
            return basenames;
        }

        public void setBasenames(List<String> basenames) {
            this.basenames = basenames;
        }

        public String getEncoding() {
            return encoding;
        }

        public void setEncoding(String encoding) {
            this.encoding = encoding;
        }

        public List<String> getSupportedLocales() {
            return supportedLocales;
        }

        public void setSupportedLocales(List<String> supportedLocales) {
            this.supportedLocales = supportedLocales;
        }

        public String getDefaultLocale() {
            return defaultLocale;
        }

        public void setDefaultLocale(String defaultLocale) {
            this.defaultLocale = defaultLocale;
        }

        public boolean isFallbackToSystemLocale() {
            return fallbackToSystemLocale;
        }

        public void setFallbackToSystemLocale(boolean fallbackToSystemLocale) {
            this.fallbackToSystemLocale = fallbackToSystemLocale;
        }

        public Duration getCacheDuration() {
            return cacheDuration;
        }

        public void setCacheDuration(Duration cacheDuration) {
            this.cacheDuration = cacheDuration;
        }

        public boolean isConfigureValidator() {
            return configureValidator;
        }

        public void setConfigureValidator(boolean configureValidator) {
            this.configureValidator = configureValidator;
        }
    }
}
