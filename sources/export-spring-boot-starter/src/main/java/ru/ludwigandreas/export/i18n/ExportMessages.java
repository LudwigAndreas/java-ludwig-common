package ru.ludwigandreas.export.i18n;

import java.util.Locale;

/**
 * Resolves the text this module writes into a file, in the locale the run asked for.
 *
 * <p>Separate from {@code web-core}'s problem-message resolution even though it is normally backed
 * by it, because the two answer different questions. A problem is resolved once, at the edge, in the
 * caller's request locale; a report resolves column headers, sheet titles, placeholders and
 * degradation markers during a run that may be executing hours later on a pooled thread with no
 * request locale at all. Passing the locale explicitly is what stops a deferred run from picking up
 * the server's default and producing an English file for a Russian requester - the bug that only
 * shows up once a report is large enough to be deferred, and therefore never in a small test.
 *
 * <p>Headers and titles are resolved <em>once per run</em>, into {@code ColumnSpec} and
 * {@code SheetSpec}, rather than per cell. At the design point a per-cell lookup would be twenty-five
 * million message-source calls.
 */
@FunctionalInterface
public interface ExportMessages {

    /**
     * Resolves one key.
     *
     * @param key    the message key
     * @param locale the run's locale
     * @param args   message-format arguments
     * @return the text, or the key itself when nothing resolves it. Returning the key rather than
     *         throwing is deliberate here and only here: the registry already refused to start on a
     *         key that resolves in neither locale, so reaching this fallback means a service
     *         overrode a bundle after startup, and a visible key in one cell is a better outcome
     *         than a failed report
     */
    String resolve(String key, Locale locale, Object... args);
}
