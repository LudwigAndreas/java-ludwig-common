package ru.ludwigandreas.notification.service.preference;

import java.time.LocalTime;

/**
 * The "not now" window a recipient chose, before it is anchored to a zone.
 *
 * <p>Separate from {@link QuietHours}, which is this window plus the zone it is judged in. The split
 * follows where the two facts come from: the window is stored with the recipient's other
 * preferences, and the zone is resolved alongside it - from the same store, from the request, or
 * from configuration - so a preference source can answer with a window without having to decide
 * which zone it means.
 *
 * @param start inclusive start of the window; null means the recipient set none
 * @param end   exclusive end
 */
public record QuietHoursWindow(LocalTime start, LocalTime end) {

    private static final QuietHoursWindow NONE = new QuietHoursWindow(null, null);

    /** A window nobody is ever inside. */
    public static QuietHoursWindow none() {
        return NONE;
    }

    /**
     * Whether this window says anything at all.
     *
     * <p>A window whose ends are equal is treated as unset rather than as "all day": 22:00 to 22:00
     * is what an unfinished form produces, and reading it as a permanent blackout would silence a
     * recipient who meant to configure nothing.
     */
    public boolean isConfigured() {
        return start != null && end != null && !start.equals(end);
    }
}
