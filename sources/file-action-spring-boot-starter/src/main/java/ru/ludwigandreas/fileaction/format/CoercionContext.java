package ru.ludwigandreas.fileaction.format;

import ru.ludwigandreas.webcore.preference.UserPreferences;

/**
 * What the coercion layer needs beyond the cell itself.
 *
 * @param preferences the submitting caller's locale and zone. Every locale-sensitive decision below
 *                    reads this and never {@code Locale.getDefault()}: the JVM default is the
 *                    container's, which is UTC and {@code en} in the datacentre and the developer's own
 *                    on a laptop, so a date parsed from it is wrong exactly where nobody is looking.
 *                    {@code RuleGroup.PRESENTATION} fails the build on the default accessors
 * @param date1904    whether the source workbook counts dates from 1904-01-01 rather than 1899-12-30.
 *                    False for a CSV, which has no serial numbers. Getting this wrong reads every date
 *                    in a Mac-authored workbook four years early
 */
public record CoercionContext(UserPreferences preferences, boolean date1904) {

    /** Falls back to the platform's fallback preferences rather than to the JVM's defaults. */
    public CoercionContext {
        preferences = preferences == null ? UserPreferences.FALLBACK : preferences;
    }
}
