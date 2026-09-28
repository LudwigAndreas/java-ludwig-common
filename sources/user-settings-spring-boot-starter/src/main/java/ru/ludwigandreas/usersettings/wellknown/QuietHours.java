package ru.ludwigandreas.usersettings.wellknown;

import java.time.LocalTime;

/**
 * A window during which a subject does not want to be disturbed.
 *
 * <p>One setting rather than three. Modelling {@code enabled}, {@code start} and {@code end} as
 * separate settings looked tidier and is wrong for a layered engine: each would resolve
 * independently, so a user overriding only the end time would silently inherit the tenant's start
 * time, and the window they would actually get is one nobody configured. Resolving the whole window
 * as a single value means a layer either supplies a window or it does not.
 *
 * <p>Stored as JSON, which is what {@code jsonEncoded} on the definition is for. There is no
 * canonical text form for a pair of times, and inventing one ({@code "22:00-07:00"}) would mean a
 * parser and a formatter of this module's own that nothing else understands.
 *
 * <p>Interpreted in the subject's own timezone - {@link WellKnownSettings#TIMEZONE} - not in the
 * server's. A quiet-hours window evaluated in UTC is quiet at the wrong time for everybody outside
 * it, which is the single most common way this feature is got wrong.
 *
 * @param enabled whether the window applies at all, so that turning quiet hours off does not require
 *                erasing the times the subject carefully chose
 * @param start   when quiet time begins, inclusive
 * @param end     when quiet time ends, exclusive
 */
public record QuietHours(boolean enabled, LocalTime start, LocalTime end) {

    /**
     * The window a subject gets when they enable quiet hours without choosing times.
     *
     * <p>Late evening to early morning, which is what "do not disturb" means to almost everybody and
     * is a better starting point than midnight to midnight (never quiet) or an empty pair the UI then
     * has to special-case.
     */
    private static final LocalTime DEFAULT_START = LocalTime.of(22, 0);
    private static final LocalTime DEFAULT_END = LocalTime.of(7, 0);

    /** The default: no quiet hours, with a sensible window already filled in for when they are enabled. */
    public static QuietHours disabled() {
        return new QuietHours(false, DEFAULT_START, DEFAULT_END);
    }

    /**
     * Whether {@code time} falls inside the window.
     *
     * <p>Handles a window that wraps past midnight, which is the normal case for quiet hours and the
     * one a naive {@code start <= t && t < end} gets exactly backwards: it would make 22:00-07:00
     * mean "quiet between seven in the morning and ten at night".
     */
    public boolean covers(LocalTime time) {
        if (!enabled || start == null || end == null || start.equals(end)) {
            return false;
        }
        if (start.isBefore(end)) {
            return !time.isBefore(start) && time.isBefore(end);
        }
        return !time.isBefore(start) || time.isBefore(end);
    }
}
