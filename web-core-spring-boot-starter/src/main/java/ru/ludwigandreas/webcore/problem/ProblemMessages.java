package ru.ludwigandreas.webcore.problem;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * Resolves problem text, preferring the application's own bundles over every module's.
 *
 * <p>Lookup order for a key is fixed and total:
 *
 * <ol>
 *   <li>the application's {@code MessageSource} - so a service can reword or re-translate any
 *       error, including one raised deep inside a library, by defining that key locally;
 *   <li>the bundles contributed by the modules on the classpath, in {@link ProblemMessageBundle}
 *       order;
 *   <li>the caller-supplied default, which is the last line of defence and in practice only shows
 *       up for a key nobody has translated yet.
 * </ol>
 *
 * <p>An HTTP error is read by an end user as often as any validation message, so it has to be
 * translatable; but a library cannot take over the application's {@code MessageSource}, and it must
 * not force every consumer to paste its keys in before its errors are legible. Preferring the
 * application and falling back through the contributed bundles satisfies both directions at once.
 */
public class ProblemMessages {

    private final MessageSource applicationMessages;
    private final MessageSource moduleMessages;
    private final List<String> basenames;

    /**
     * @param applicationMessages the application's own source, or {@code null} if it has none
     * @param bundles             every module's contributed bundle, in any order
     */
    public ProblemMessages(MessageSource applicationMessages, Collection<ProblemMessageBundle> bundles) {
        this.applicationMessages = applicationMessages;
        this.basenames = (bundles == null ? List.<ProblemMessageBundle>of() : bundles).stream()
                .sorted(Comparator.comparingInt(ProblemMessageBundle::order))
                .map(ProblemMessageBundle::basename)
                .distinct()
                .toList();

        ResourceBundleMessageSource modules = new ResourceBundleMessageSource();
        // setBasenames honours the given order for resolution, which is what makes the contributed
        // bundles' `order` meaningful rather than dependent on bean-discovery order.
        modules.setBasenames(basenames.toArray(String[]::new));
        modules.setDefaultEncoding("UTF-8");
        // Without this, a locale with no bundle silently picks up the server's own locale - the
        // API's language would then depend on how the container happens to be configured.
        modules.setFallbackToSystemLocale(false);
        // The code is never a usable message, and returning it would defeat every default below.
        modules.setUseCodeAsDefaultMessage(false);
        this.moduleMessages = modules;
    }

    /** Convenience for a mapper or a test that has no application {@code MessageSource}. */
    public static ProblemMessages ofBundles(String... basenames) {
        return new ProblemMessages(null, Arrays.stream(basenames)
                .map(ProblemMessageBundle::of)
                .toList());
    }

    /** Resolves {@code code} in the current request's locale, or returns {@code defaultMessage}. */
    public String get(String code, Object[] args, String defaultMessage) {
        return get(code, args, defaultMessage, LocaleContextHolder.getLocale());
    }

    public String get(String code, Object[] args, String defaultMessage, Locale locale) {
        return resolve(code, args, locale).orElse(defaultMessage);
    }

    /**
     * Resolves {@code code}, or an empty result if no bundle in the chain defines it.
     *
     * <p>Separate from {@link #get} because "there is no translation for this" is a decision some
     * callers have to make rather than paper over - see how the OData mapper keeps a library's
     * English developer message as the detail only while its key is untranslated.
     */
    public Optional<String> resolve(String code, Object[] args, Locale locale) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        Locale target = locale == null ? LocaleContextHolder.getLocale() : locale;
        if (applicationMessages != null) {
            try {
                return Optional.of(applicationMessages.getMessage(code, args, target));
            } catch (NoSuchMessageException ignored) {
                // Not defined locally: fall through to the modules' own bundles.
            }
        }
        return Optional.ofNullable(moduleMessages.getMessage(code, args, null, target));
    }

    /** The contributed basenames, in resolution order. Exposed for diagnostics and tests. */
    public List<String> basenames() {
        return basenames;
    }
}
