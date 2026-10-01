package ru.ludwigandreas.webcore.preference;

import java.util.Locale;
import java.util.TimeZone;
import java.util.function.Supplier;
import org.springframework.context.i18n.TimeZoneAwareLocaleContext;

/**
 * The {@link org.springframework.context.i18n.LocaleContext} this package publishes, carrying the
 * resolved {@link UserPreferences} rather than only a locale and a {@link TimeZone}.
 *
 * <h2>Why a type of our own rather than SimpleTimeZoneAwareLocaleContext</h2>
 *
 * <p>Two reasons, both load-bearing.
 *
 * <p>The zone survives the round trip exactly. Spring's holder carries a {@link TimeZone}, and
 * {@code TimeZone.getTimeZone(zone).toZoneId()} is not an identity - a {@code ZoneOffset} goes in
 * and a {@code GMT+hh:mm} zone id comes out. Keeping the {@link UserPreferences} alongside means
 * {@link UserPreferences#current()} returns what was resolved, and the {@link TimeZone} view is
 * derived rather than the other way round.
 *
 * <p>And it is what makes {@link UserPreferences#currentIfResolved()} answerable at all. "Were
 * preferences resolved for this thread, or am I looking at a default?" cannot be asked of a
 * {@code SimpleTimeZoneAwareLocaleContext}, because a context built from defaults and a context
 * built from a user's stored choice are the same object with the same two fields. An audit record
 * that cannot tell those apart claims the caller chose the deployment's default.
 *
 * <h2>Laziness</h2>
 *
 * <p>The supplier form is what the resolver publishes per request. A dispatch that never formats
 * anything - an actuator probe, a 204, a byte-range download - consults no preference source and
 * performs no settings read; a dispatch that formats twenty fields performs one. The memoisation is
 * deliberately not synchronised: a {@code LocaleContext} is published to one request thread, and a
 * lock here would be paid for by every request to protect against a handoff that
 * {@link UserPreferences#bind()} exists to make explicit. Two resolutions on a thread that shared
 * one anyway produce equal values, so the degenerate case is a wasted cache read rather than a
 * wrong answer.
 */
public final class UserPreferenceLocaleContext implements TimeZoneAwareLocaleContext {

    private final Supplier<UserPreferences> source;

    private UserPreferences resolved;

    /**
     * A context over preferences that are already known.
     *
     * @param preferences the resolved preferences
     */
    public UserPreferenceLocaleContext(UserPreferences preferences) {
        if (preferences == null) {
            throw new IllegalArgumentException("A preference locale context needs preferences");
        }
        this.resolved = preferences;
        this.source = () -> preferences;
    }

    /**
     * A context that resolves on first use and remembers the answer.
     *
     * @param source resolves the preferences; must not return null
     */
    public UserPreferenceLocaleContext(Supplier<UserPreferences> source) {
        if (source == null) {
            throw new IllegalArgumentException("A lazy preference locale context needs a supplier");
        }
        this.source = source;
    }

    /**
     * The resolved preferences, resolving them now if this context is still lazy.
     *
     * @return the preferences; never null
     */
    public UserPreferences preferences() {
        if (resolved == null) {
            UserPreferences answer = source.get();
            resolved = answer == null ? UserPreferenceDefaults.get() : answer;
        }
        return resolved;
    }

    /** Whether the preferences have been resolved, for a test that asserts nothing was read. */
    public boolean isResolved() {
        return resolved != null;
    }

    @Override
    public Locale getLocale() {
        return preferences().locale();
    }

    @Override
    public TimeZone getTimeZone() {
        return preferences().timeZone();
    }

    @Override
    public String toString() {
        return resolved == null ? "UserPreferenceLocaleContext[unresolved]"
                : "UserPreferenceLocaleContext" + resolved;
    }
}
