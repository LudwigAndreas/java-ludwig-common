package ru.ludwigandreas.export.api;

import java.time.ZoneId;
import java.util.Locale;
import ru.ludwigandreas.webcore.preference.UserPreferences;

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
 * <h2>Why this holds UserPreferences rather than the two values</h2>
 *
 * <p>It used to be {@code RenderContext(Locale locale, ZoneId zone)} - which is, field for field,
 * {@code web-core}'s {@link UserPreferences}, written a second time. The duplication was invisible
 * while there was nothing to duplicate; once the platform had one caller-preference contract, this
 * was the clearest restatement of it in the repository, and it is what
 * {@code RuleGroup.PRESENTATION}'s {@code noSecondCallerPreferenceType} rule is written to catch.
 *
 * <p>Holding the contract rather than being replaced by it keeps the renderer SPI's vocabulary:
 * {@code render.locale()} and {@code render.zone()} are what every renderer in the module already
 * says, they read better at a cell renderer's call site than {@code preferences.locale()} would, and
 * leaving them in place meant this consolidation touched one construction site rather than twenty-one
 * call sites. What it buys is that a report is now rendered in the <em>caller's</em> locale and zone
 * by construction, because the only thing that can produce the pair is the resolution chain.
 *
 * @param preferences the caller's locale and zone, resolved by {@code web-core}'s preference chain
 */
public record RenderContext(UserPreferences preferences) {

    public RenderContext {
        if (preferences == null) {
            throw new IllegalArgumentException("A RenderContext needs the caller's preferences");
        }
    }

    /**
     * A context over a locale and a zone named directly.
     *
     * <p>For a test and for the planner, which has the pair rather than the record. Not a second
     * constructor: a canonical {@code (Locale, ZoneId)} constructor would make this record declare
     * those two components again, which is the restatement the rule exists to prevent.
     *
     * @param locale the caller's locale
     * @param zone   the caller's zone
     * @return the render context
     */
    public static RenderContext of(Locale locale, ZoneId zone) {
        return new RenderContext(new UserPreferences(locale, zone));
    }

    /** The caller's locale, used for headers, placeholders, number and date presentation. */
    public Locale locale() {
        return preferences.locale();
    }

    /** The timezone every instant in the report is presented in. */
    public ZoneId zone() {
        return preferences.zone();
    }
}
