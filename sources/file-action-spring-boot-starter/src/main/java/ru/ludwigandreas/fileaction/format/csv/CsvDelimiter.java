package ru.ludwigandreas.fileaction.format.csv;

/**
 * Which character separates fields, worked out from the file rather than configured.
 *
 * <h2>Why this is sniffed and not a setting</h2>
 *
 * <p>A setting would be wrong per user rather than per deployment. Excel writes the delimiter the
 * <em>author's</em> locale calls for - a semicolon wherever the decimal separator is a comma, which is
 * most of Europe and all of Russia - so one team's exports are comma-delimited and another's are
 * semicolon-delimited, in the same deployment, on the same action, on the same day. A configured
 * delimiter puts every row of half the submitted files into the first column, which presents as "the
 * import is broken" rather than as a configuration mistake.
 *
 * <p>So the delimiter is decided per file, from its header line.
 */
public enum CsvDelimiter {

    /** RFC 4180's delimiter, and what a machine-generated file almost always uses. */
    COMMA(','),

    /** What Excel writes in any locale whose decimal separator is a comma. */
    SEMICOLON(';'),

    /** Tab-separated, which users produce by pasting out of a spreadsheet into a text editor. */
    TAB('\t'),

    /** Rare, but unambiguous when present, and trivial to support once the others are. */
    PIPE('|');

    private final char character;

    CsvDelimiter(char character) {
        this.character = character;
    }

    /** The separator character. */
    public char character() {
        return character;
    }
}
