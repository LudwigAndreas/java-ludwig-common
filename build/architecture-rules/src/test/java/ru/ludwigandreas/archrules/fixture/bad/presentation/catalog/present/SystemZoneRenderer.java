package ru.ludwigandreas.archrules.fixture.bad.presentation.catalog.present;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;

/**
 * The defect, written the way it actually gets written.
 *
 * <p>Nothing here looks wrong. It is a short, readable, correct-in-development renderer, and that is
 * the point of the fixture: the call to the JVM's default is the whole mistake, and on the machine
 * where somebody would notice, it gives the right answer.
 */
public class SystemZoneRenderer {

    /** Renders an instant in the container's zone, which is the defect. */
    public String render(Instant instant) {
        return instant.atZone(ZoneId.systemDefault()).toString();
    }

    /** And formats a number in the container's locale, which is the same defect for a separator. */
    public String amount(double value) {
        return String.format(Locale.getDefault(), "%.2f", value);
    }
}
