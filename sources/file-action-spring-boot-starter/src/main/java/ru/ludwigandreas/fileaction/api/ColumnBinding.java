package ru.ludwigandreas.fileaction.api;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One column of a {@link RowBinding}: the field it fills, the header the user's file calls it, the
 * other headers that mean the same thing, and whether it may be absent.
 *
 * @param field    the record component this column fills. Matched to the row record's constructor
 *                 parameter by name, which is why the row type must be a record compiled with
 *                 parameter names - checked once, at startup, by {@code RowBinding}
 * @param header   the canonical header text, which is also what the generated template writes and what
 *                 a {@link RowAddress} names
 * @param aliases  other header texts that bind to the same field. Case- and whitespace-insensitive,
 *                 like {@code header} itself
 * @param required whether a file with no matching header is refused. A required column's absence is a
 *                 file-level failure, not a per-row one: every row would reject for the same reason,
 *                 and a reject report with one entry per row says nothing a single message does not
 */
public record ColumnBinding(String field, String header, Set<String> aliases, boolean required) {

    /** Normalises the alias set and rejects a column that names no field or no header. */
    public ColumnBinding {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("A ColumnBinding needs the field it fills");
        }
        if (header == null || header.isBlank()) {
            throw new IllegalArgumentException(
                    "A ColumnBinding needs a header; column '" + field + "' has none");
        }
        aliases = aliases == null ? Set.of() : Set.copyOf(aliases);
    }

    /**
     * Every header text that binds to this column, normalised for matching.
     *
     * <p>Normalisation is lower-casing in {@link Locale#ROOT} and collapsing whitespace, because a
     * header a human typed carries trailing spaces, a non-breaking space pasted from a web page, and
     * whatever capitalisation the author of the template felt like. A binding that matched exactly
     * would reject a file that looks correct to everyone who opens it.
     *
     * <p>{@link Locale#ROOT} rather than the caller's locale deliberately: a header is an identifier
     * in the file's own terms, not user-facing text, and lower-casing an identifier in the Turkish
     * locale turns {@code I} into a dotless {@code i} and stops it matching. This is the one place in
     * the module where not using the caller's locale is correct, which is why it is said here.
     *
     * @return the normalised header and aliases, in declaration order
     */
    public Set<String> matchableHeaders() {
        Set<String> all = new LinkedHashSet<>();
        all.add(normalise(header));
        for (String alias : aliases) {
            all.add(normalise(alias));
        }
        return all;
    }

    /**
     * The no-break space, which is what a header pasted out of a web page or a PDF is separated with.
     *
     * <p>Built from its code point rather than written as a character literal, for the reason
     * {@code export}'s {@code CsvProfile} gives about the byte-order mark: a {@code \}{@code u00A0}
     * escape is processed before the source is lexed - including inside a comment - so the character
     * would sit invisibly in this file, and Checkstyle's {@code NonAsciiSourceText} would fail the build
     * on a line whose problem nobody could see. It was written as a literal once here and did exactly
     * that.
     */
    private static final char NO_BREAK_SPACE = (char) 0x00A0;

    /**
     * Normalises one header text for matching.
     *
     * @param text the text as it appears in a file or a declaration
     * @return the normalised form, or {@code null} if {@code text} was null
     */
    public static String normalise(String text) {
        if (text == null) {
            return null;
        }
        return text.strip()
                .replace(NO_BREAK_SPACE, ' ')
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    /** A required column with no aliases. */
    static ColumnBinding required(String field, String header, List<String> aliases) {
        return new ColumnBinding(field, header, new LinkedHashSet<>(aliases), true);
    }

    /** An optional column with no aliases. */
    static ColumnBinding optional(String field, String header, List<String> aliases) {
        return new ColumnBinding(field, header, new LinkedHashSet<>(aliases), false);
    }
}
