package ru.ludwigandreas.ingest.engine;

import ru.ludwigandreas.storage.api.ObjectUri;

/**
 * Every place this module derives one object's location from another's.
 *
 * <h2>Why these four live together</h2>
 *
 * <p>A sentinel, a receipt, an archived copy and the suffix discovery excludes by are all the same
 * operation - take a location and a template, produce another location - and all four are the
 * category of code that is obviously right and quietly wrong. A sentinel resolved into the wrong
 * prefix is a task that silently never reads anything; an archive location that loses its prefix is a
 * source object copied over itself and then deleted.
 *
 * <p>Gathered here, and public, so that they are one testable contract rather than four private
 * methods each exercised only through the component that happens to call it. The alternative -
 * package-private helpers on {@code ArrivalDetector}, {@code ReceiptWriter} and {@code SourceArchiver}
 * - would put them out of reach of this module's test layout, and a naming rule that is only
 * exercised end-to-end is a naming rule whose edge cases are not exercised at all.
 */
public final class IngestNaming {

    /** The token a template uses to stand for the data object's own name. */
    public static final String NAME_TOKEN = "{name}";

    private IngestNaming() {
    }

    /**
     * Where a data object's sentinel would be.
     *
     * <p>A template containing a slash names a key outright, which supports a partner who keeps
     * sentinels in their own prefix; anything else is resolved next to the data object, which is the
     * overwhelmingly common arrangement.
     *
     * @param uri      the data object
     * @param template the sentinel template, with {@link #NAME_TOKEN} for the object's own name
     * @return where the sentinel would be
     */
    public static ObjectUri sentinelFor(ObjectUri uri, String template) {
        String rendered = template.replace(NAME_TOKEN, uri.name());
        if (rendered.contains("/")) {
            return uri.withKey(rendered.startsWith("/") ? rendered.substring(1) : rendered);
        }
        int slash = uri.key().lastIndexOf('/');
        String directory = slash < 0 ? "" : uri.key().substring(0, slash + 1);
        return uri.withKey(directory + rendered);
    }

    /**
     * The suffix a sentinel's name ends in, so discovery can exclude sentinels from the candidates.
     *
     * <p>A sentinel lives next to its data object and will match a loose pattern. Ingesting
     * {@code data.csv.done} as though it were data produces a run that reads a zero-byte object,
     * applies nothing, balances perfectly and completes - and then the identity constraint remembers
     * it, so the mistake is permanent. The exclusion is therefore structural rather than left to the
     * pattern.
     *
     * @param template the sentinel template, possibly blank
     * @return the suffix, or {@code null} when sentinels live elsewhere and there is nothing to
     *         exclude from this prefix
     */
    public static String sentinelSuffix(String template) {
        if (template == null || template.isBlank() || !template.startsWith(NAME_TOKEN)) {
            return null;
        }
        String suffix = template.substring(NAME_TOKEN.length());
        return suffix.isEmpty() ? null : suffix;
    }

    /**
     * Where a run's receipt object goes.
     *
     * @param source   the object that was ingested
     * @param template the key template
     * @return the receipt's location, in the source's own container
     */
    public static ObjectUri receiptFor(ObjectUri source, String template) {
        return source.withKey(template.replace(NAME_TOKEN, source.name()));
    }

    /**
     * Where an archived copy of a source object goes.
     *
     * <p>Under the prefix, keeping only the object's own name: a prefix of {@code processed/} turns
     * {@code in/2026/catalogue.csv} into {@code processed/catalogue.csv}. Flattening matches what the
     * prefix is for - getting the object out of the drop prefix - and the date is already in the name
     * of any file where it matters.
     *
     * @param source the object
     * @param prefix the archive prefix, however its slashes were written
     * @return where the copy goes
     */
    public static ObjectUri archivedFor(ObjectUri source, String prefix) {
        String normalised = prefix == null || prefix.isBlank() ? "" : prefix;
        while (normalised.startsWith("/")) {
            normalised = normalised.substring(1);
        }
        if (!normalised.isEmpty() && !normalised.endsWith("/")) {
            normalised = normalised + "/";
        }
        return source.withKey(normalised + source.name());
    }
}
