package ru.ludwigandreas.webcore.preference;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

/**
 * Folds the ordered {@link UserPreferenceSource}s into one answer, one dimension at a time.
 *
 * <h2>The supported-locale restriction is applied here, after resolution</h2>
 *
 * <p>Not inside the header parse, where Spring's {@code AcceptHeaderLocaleResolver} puts it. A
 * stored locale never passes through a header, so a restriction that lived in the header parse would
 * let a stored {@code de} through to a service that ships only {@code en} and {@code ru} bundles -
 * and the response comes back with a translated {@code title} and English everything else. The
 * {@code i18n-bundles} capability requires that an unsupported locale yields the default *in full*,
 * and that has to hold for whichever source supplied it.
 *
 * <p>Matching is by language, and a match keeps the <em>caller's</em> tag rather than substituting the
 * supported one. A caller naming {@code ru-RU} when the service supports {@code ru} is resolved as
 * {@code ru-RU}, not narrowed to {@code ru}. Narrowing was the obvious implementation and it silently
 * loses information: {@code MessageSource} already falls back from {@code ru-RU} to the {@code ru}
 * bundle on its own, so the region costs nothing there, while several things genuinely derive from it.
 * {@code WeekFields.of(Locale.forLanguageTag("ru")).getFirstDayOfWeek()} is {@code SUNDAY} and
 * {@code WeekFields.of(Locale.forLanguageTag("ru-RU"))} is {@code MONDAY}, because the JDK carries
 * first-day-of-week as region data - so a resolver that narrowed to the supported language would hand
 * every Russian user an American calendar. Measured, not assumed; it is what made the test for
 * {@code UserPreferences.firstDayOfWeek()} fail the first time it ran.
 *
 * <p>An empty supported-locale list means no restriction, which is what makes this usable by a
 * service that resolves preferences for formatting and ships no bundles at all.
 */
public class UserPreferenceResolver {

    private final List<UserPreferenceSource> sources;

    private final List<Locale> supportedLocales;

    /**
     * @param sources          every source; reordered here by {@code @Order} so a caller does not
     *                         have to have sorted them
     * @param supportedLocales the locales the service has bundles for; empty means no restriction
     */
    public UserPreferenceResolver(List<UserPreferenceSource> sources, List<Locale> supportedLocales) {
        List<UserPreferenceSource> ordered = new ArrayList<>(sources);
        ordered.sort(AnnotationAwareOrderComparator.INSTANCE);
        this.sources = List.copyOf(ordered);
        this.supportedLocales = List.copyOf(supportedLocales);
    }

    /**
     * Resolves both dimensions, each from the first source that answers for it.
     *
     * @return the resolved preferences; never null, because the configured source always answers
     */
    public UserPreferences resolve() {
        UserPreferences defaults = UserPreferenceDefaults.get();
        Locale locale = first(UserPreferenceSource::locale).map(this::supported).orElse(defaults.locale());
        ZoneId zone = first(UserPreferenceSource::zone).orElse(defaults.zone());
        return new UserPreferences(locale, zone);
    }

    /** The active sources, in order, for the startup log. */
    public List<String> sourceNames() {
        return sources.stream().map(UserPreferenceSource::sourceName).toList();
    }

    private <T> Optional<T> first(Function<UserPreferenceSource, Optional<T>> dimension) {
        for (UserPreferenceSource source : sources) {
            Optional<T> answer = dimension.apply(source);
            if (answer.isPresent()) {
                return answer;
            }
        }
        return Optional.empty();
    }

    /**
     * The nearest supported locale, or the configured default.
     *
     * <p>Exact tag first, then a language match - which answers the caller's own tag, region included -
     * then the default. Returning the requested locale unchanged when nothing matches at all is what
     * produces a half-translated response, so the default is returned instead: deliberately losing the
     * caller's request rather than partly honouring it.
     */
    private Locale supported(Locale requested) {
        if (supportedLocales.isEmpty()) {
            return requested;
        }
        for (Locale candidate : supportedLocales) {
            if (candidate.equals(requested)) {
                return candidate;
            }
        }
        for (Locale candidate : supportedLocales) {
            if (!candidate.getLanguage().isEmpty() && candidate.getLanguage().equals(requested.getLanguage())) {
                return requested;
            }
        }
        return UserPreferenceDefaults.get().locale();
    }
}
