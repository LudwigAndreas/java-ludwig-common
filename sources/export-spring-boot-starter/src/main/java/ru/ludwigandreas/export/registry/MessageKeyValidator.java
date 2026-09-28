package ru.ludwigandreas.export.registry;

import java.util.Locale;

/**
 * Answers whether a message key resolves, for the locales the estate ships.
 *
 * <p>An interface rather than a direct {@code MessageSource} dependency because the question the
 * registry asks is not the one a {@code MessageSource} answers well: resolving a key normally falls
 * back to the key itself or to the default locale, so "does this render in Russian" cannot be asked
 * without knowing which fallback was configured. Pushing that behind one method keeps the decision
 * in one place, and lets a test supply a fixed key set instead of a message source.
 *
 * <p>The check exists because the failure it catches is invisible until a user hits it: a column
 * header added to the English bundle and forgotten in the Russian one renders as
 * {@code report.orders.column.total} in the file a Russian-speaking user downloads. Checkstyle's
 * {@code Translation} rule catches a key missing from one bundle when the other has it; this
 * catches a key that is in neither, which is the more common mistake.
 */
@FunctionalInterface
public interface MessageKeyValidator {

    /**
     * Whether the key resolves to real text in this locale.
     *
     * @param key    the message key
     * @param locale the locale to resolve it in
     * @return false when the key is absent, blank, or resolves only to itself
     */
    boolean resolves(String key, Locale locale);
}
