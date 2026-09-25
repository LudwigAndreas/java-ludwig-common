package ru.ludwigandreas.export.api;

import java.time.ZoneId;
import java.util.Locale;

/**
 * The presentation settings one run was asked for, passed to every cell renderer.
 *
 * <p>Locale and zone travel with the render call rather than being read from the JVM's defaults,
 * because an asynchronous run executes on a pooled thread in a container whose defaults are
 * {@code en_US} and UTC no matter who asked for the report. A renderer that consulted
 * {@code Locale.getDefault()} would produce a different file depending on whether the run happened
 * to be small enough to execute synchronously - the hardest class of reporting bug to reproduce,
 * because it disappears the moment anyone tries it with a small parameter set.
 *
 * @param locale the caller's locale, used for headers, placeholders, number and date presentation,
 *               and for the CSV {@code excel} profile's list separator
 * @param zone   the timezone every instant in the report is presented in
 */
public record RenderContext(Locale locale, ZoneId zone) {

    public RenderContext {
        if (locale == null) {
            throw new IllegalArgumentException("A RenderContext needs a locale");
        }
        if (zone == null) {
            throw new IllegalArgumentException("A RenderContext needs a zone");
        }
    }
}
