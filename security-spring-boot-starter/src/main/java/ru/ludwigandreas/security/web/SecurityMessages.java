package ru.ludwigandreas.security.web;

import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * Resolves the module's user-facing error text, preferring the application's own bundles.
 *
 * <p>A 401 or a 403 is read by an end user as often as any validation error, so it has to speak their
 * language. But a library cannot simply take over the application's {@code MessageSource}, and it must
 * not force every consumer to copy its keys in before it will work. So: look the key up in the
 * application's bundle first - which lets a service override any message without touching this module -
 * and fall back to the bundle shipped inside the jar.
 */
public class SecurityMessages {

    private static final String MODULE_BUNDLE = "i18n/ludwig-security-messages";

    private final MessageSource applicationMessages;
    private final MessageSource moduleMessages;

    public SecurityMessages(MessageSource applicationMessages) {
        this.applicationMessages = applicationMessages;
        ResourceBundleMessageSource fallback = new ResourceBundleMessageSource();
        fallback.setBasename(MODULE_BUNDLE);
        fallback.setDefaultEncoding("UTF-8");
        // Without this, a locale with no bundle silently picks up the server's own locale - the API's
        // language would then depend on how the container happens to be configured.
        fallback.setFallbackToSystemLocale(false);
        fallback.setUseCodeAsDefaultMessage(true);
        this.moduleMessages = fallback;
    }

    public String get(String code, String defaultMessage) {
        Locale locale = LocaleContextHolder.getLocale();
        if (applicationMessages != null) {
            try {
                return applicationMessages.getMessage(code, null, locale);
            } catch (NoSuchMessageException ignored) {
                // fall through to the module's own bundle
            }
        }
        String resolved = moduleMessages.getMessage(code, null, defaultMessage, locale);
        return resolved == null || resolved.equals(code) ? defaultMessage : resolved;
    }
}
