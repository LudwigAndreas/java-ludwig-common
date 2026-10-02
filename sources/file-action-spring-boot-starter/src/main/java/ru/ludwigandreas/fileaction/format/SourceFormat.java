package ru.ludwigandreas.fileaction.format;

/**
 * The formats this module reads. A closed set, and closed on purpose.
 *
 * <p>Every format added here is a parser exposed to a file a user chose, so the list is short and each
 * entry earns its place. What is deliberately absent:
 *
 * <ul>
 *   <li><b>Legacy {@code .xls} (BIFF).</b> {@code HSSFWorkbook} has no streaming mode - there is no SAX
 *       equivalent, because the format is a compound binary document rather than XML - so reading one
 *       means a heap proportional to a user-supplied file. That is the single guarantee this module
 *       exists to make, so the format is refused with a message telling the user to save as
 *       {@code .xlsx}. It is detected rather than merely unmatched, so the message can say that.</li>
 *   <li><b>{@code .ods}, {@code SpreadsheetML}, HTML tables.</b> Each is another parser for a format
 *       nobody has asked for.</li>
 *   <li><b>Archives of files.</b> A ZIP of CSVs is a different feature - it needs a per-entry lifecycle -
 *       and accepting one here would mean the ZIP-safety budget guarding a path that then recurses.</li>
 * </ul>
 */
public enum SourceFormat {

    /**
     * OOXML workbook, read through POI's {@code XSSFReader} SAX path.
     *
     * <p>A ZIP container, which is why the archive budget exists.
     */
    XLSX("xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),

    /**
     * Delimiter-separated text.
     *
     * <p>Includes what users call "Excel CSV" - semicolon-delimited, byte-order-marked, in a Windows
     * code page - because that is what the overwhelming majority of submitted CSVs are.
     */
    CSV("csv", "text/csv");

    private final String extension;
    private final String mediaType;

    SourceFormat(String extension, String mediaType) {
        this.extension = extension;
        this.mediaType = mediaType;
    }

    /** The canonical extension, for the generated template's filename. */
    public String extension() {
        return extension;
    }

    /** The canonical media type, for a response that serves a file of this format. */
    public String mediaType() {
        return mediaType;
    }
}
