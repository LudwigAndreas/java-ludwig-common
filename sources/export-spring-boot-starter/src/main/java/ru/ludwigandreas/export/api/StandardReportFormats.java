package ru.ludwigandreas.export.api;

/**
 * The two formats this module ships writers for.
 *
 * <p>An enum rather than a set of constants because these two are genuinely fixed: they are the ones
 * whose writers live in this module and whose capabilities the module's own tests assert. A format
 * added by a service implements {@link ReportFormat} directly and is registered by contributing a
 * {@link ReportWriterFactory}; it does not belong here, and nothing in the engine requires it to.
 */
public enum StandardReportFormats implements ReportFormat {

    /**
     * Office Open XML, written with Apache POI's streaming workbook.
     *
     * <p>The ceiling is the format's own: the OOXML specification allows 1,048,576 rows per sheet,
     * and a workbook that exceeds it is rejected by Excel rather than truncated by it. A report
     * larger than that rolls over onto continuation sheets.
     */
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx",
            FormatCapabilities.spreadsheet(1_048_576L)),

    /**
     * Delimiter-separated text.
     *
     * <p>No sheets, no styles, no typed cells and no ceiling. The absence of a totals row is a
     * capability decision rather than an oversight: CSV is usually read by a machine, and a
     * grand-total row appended to a data file is a record with the wrong shape in it.
     */
    CSV("csv", "text/csv", "csv", FormatCapabilities.flatText());

    private final String id;
    private final String mediaType;
    private final String fileExtension;
    private final FormatCapabilities capabilities;

    StandardReportFormats(String id, String mediaType, String fileExtension,
                          FormatCapabilities capabilities) {
        this.id = id;
        this.mediaType = mediaType;
        this.fileExtension = fileExtension;
        this.capabilities = capabilities;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String mediaType() {
        return mediaType;
    }

    @Override
    public String fileExtension() {
        return fileExtension;
    }

    @Override
    public FormatCapabilities capabilities() {
        return capabilities;
    }
}
