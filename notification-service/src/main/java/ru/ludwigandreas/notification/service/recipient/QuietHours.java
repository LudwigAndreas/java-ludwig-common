package ru.ludwigandreas.notification.service.recipient;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * A recipient's "not now" window, evaluated in their own zone.
 *
 * @param start inclusive start of the quiet window; null disables quiet hours entirely
 * @param end   exclusive end
 * @param zone  the recipient's zone - the only place the window means anything
 */
public record QuietHours(LocalTime start, LocalTime end, ZoneId zone) {

    /** A window nobody is ever inside, used when a recipient has set none. */
    public static QuietHours none(ZoneId zone) {
        return new QuietHours(null, null, zone);
    }

    public boolean isConfigured() {
        return start != null && end != null && !start.equals(end);
    }

    /**
     * Whether {@code at} falls inside the window.
     *
     * <p>Handles a window that wraps midnight, which is the normal case: 22:00 to 07:00 is what
     * people actually set, and the naive {@code start <= t && t < end} test returns false for every
     * instant of it. Getting this wrong means quiet hours silently do nothing for exactly the
     * recipients who configured them most deliberately.
     */
    public boolean contains(java.time.Instant at) {
        if (!isConfigured()) {
            return false;
        }
        LocalTime local = ZonedDateTime.ofInstant(at, zone).toLocalTime();
        if (start.isBefore(end)) {
            return !local.isBefore(start) && local.isBefore(end);
        }
        return !local.isBefore(start) || local.isBefore(end);
    }

    /**
     * The first instant at or after {@code at} that is outside the window.
     *
     * <p>Used to defer rather than drop: a marketing notification that arrives at 03:00 is scheduled
     * for the end of the window instead of being thrown away, because the caller was told the request
     * was accepted and something has to arrive.
     */
    public java.time.Instant nextOpening(java.time.Instant at) {
        if (!contains(at)) {
            return at;
        }
        ZonedDateTime local = ZonedDateTime.ofInstant(at, zone);
        ZonedDateTime opening = local.with(end);
        if (!opening.toInstant().isAfter(at)) {
            // The window's end has already passed today, which is what a wrapping window looks like
            // from inside its late-evening half - the opening is tomorrow morning.
            opening = opening.plusDays(1);
        }
        return opening.toInstant();
    }
}
