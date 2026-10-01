package ru.ludwigandreas.webcore.preference;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.i18n.LocaleContextHolder;

/**
 * The deployment's configured preferences, for the threads that have no caller.
 *
 * <h2>Why a mutable static exists here</h2>
 *
 * <p>Because {@link UserPreferences#current()} is a static read and has to answer on a scheduled
 * job, a Kafka listener and a unit test, where there is no request, no bound scope and no injected
 * bean in reach. The alternative answers are both worse: a hard-coded {@code en}/UTC means a
 * Russian-locale deployment's scheduled digests render in English, and
 * {@code Locale.getDefault()}/{@code ZoneId.systemDefault()} means they render in whatever the
 * container was configured with, which is the defect this whole package exists to remove.
 *
 * <p>It is written exactly once, by {@code WebCorePreferenceAutoConfiguration} at startup, from
 * {@code ludwig.web.i18n.default-locale} and {@code ludwig.web.preferences.default-zone}. Before
 * that - and in a unit test that stands up no context - the value is
 * {@link UserPreferences#FALLBACK}.
 *
 * <p>{@link #install} also sets Spring's own process-wide defaults
 * ({@link LocaleContextHolder#setDefaultLocale} and
 * {@link LocaleContextHolder#setDefaultTimeZone}), so that code reading Spring's holder directly off
 * a request thread gets the same answer as code reading this. One install site setting both is the
 * point: two process-wide defaults that can disagree is strictly worse than one, and Spring's
 * holder falls back to {@code Locale.getDefault()} and {@code TimeZone.getDefault()} if nobody sets
 * it.
 */
public final class UserPreferenceDefaults {

    private static final AtomicReference<UserPreferences> CONFIGURED =
            new AtomicReference<>(UserPreferences.FALLBACK);

    private UserPreferenceDefaults() {
    }

    /**
     * The deployment's defaults.
     *
     * @return the configured defaults, or {@link UserPreferences#FALLBACK} before startup has set
     *         them
     */
    public static UserPreferences get() {
        return CONFIGURED.get();
    }

    /**
     * Installs the deployment's defaults. Called once, from autoconfiguration.
     *
     * @param preferences the configured defaults
     */
    public static void install(UserPreferences preferences) {
        if (preferences == null) {
            throw new IllegalArgumentException("Configured defaults must not be null");
        }
        CONFIGURED.set(preferences);
        LocaleContextHolder.setDefaultLocale(preferences.locale());
        LocaleContextHolder.setDefaultTimeZone(preferences.timeZone());
    }

    /**
     * Restores the built-in fallback. For tests, which must not leak one test's configuration into
     * the next - a process-wide default that survives a test class is the kind of coupling that
     * makes a suite pass in one order and fail in another.
     */
    public static void reset() {
        CONFIGURED.set(UserPreferences.FALLBACK);
        LocaleContextHolder.setDefaultLocale(null);
        LocaleContextHolder.setDefaultTimeZone(null);
    }
}
