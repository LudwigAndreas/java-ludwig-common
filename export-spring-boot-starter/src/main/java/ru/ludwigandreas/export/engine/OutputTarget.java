package ru.ludwigandreas.export.engine;

import java.util.List;
import java.util.Map;
import ru.ludwigandreas.export.api.MultiSheetStrategy;
import ru.ludwigandreas.export.api.ReportFormat;
import ru.ludwigandreas.export.api.SheetSpec;

/**
 * One file a run is going to produce, fully decided before the first row is read.
 *
 * <h2>Why every decision is made here rather than during the run</h2>
 *
 * <p>Which sheets this format receives, what the file is called, what media type it carries and
 * whether it gets a totals row are all consequences of the format's capabilities meeting the
 * definition's demands. Settling them up front means the writer is handed facts rather than
 * questions, and - more importantly - means a combination that cannot work fails before anything has
 * been written, rather than on the sheet where it first matters.
 *
 * <p>It is also what makes multi-format single-pass honest. Two formats can legitimately want
 * different sheets from the same run: XLSX takes all of them, CSV under
 * {@link MultiSheetStrategy#PRIMARY_ONLY} takes one. Holding that per output rather than per run is
 * what lets one walk over the rows feed both without either of them getting the other's answer.
 *
 * @param format      the format being produced
 * @param sheets      the sheets this output receives, in declaration order, already narrowed by the
 *                    multi-sheet strategy and by the requester's column visibility
 * @param omittedSheets sheet ids this output does not receive. Non-empty only under
 *                    {@code PRIMARY_ONLY}, and recorded on the run and reported by the download, so
 *                    that a partial file is never presented as a whole one
 * @param strategy    what the definition asked for when a format has fewer sheets than it declares
 * @param fileExtension the extension the file actually gets. Not always the format's own: several
 *                    sheets as CSV under {@code ZIP} produce an archive
 * @param mediaType   what the download endpoint reports, for the same reason
 * @param options     the request's per-format options, already validated against this format's
 *                    accepted set
 * @param totalsRow   whether a totals row is written, which needs both the definition to ask for one
 *                    and the format to be able to carry it
 */
public record OutputTarget(
        ReportFormat format,
        List<SheetSpec> sheets,
        List<String> omittedSheets,
        MultiSheetStrategy strategy,
        String fileExtension,
        String mediaType,
        Map<String, String> options,
        boolean totalsRow) {

    /** What a file becomes when several sheets are archived into one. */
    public static final String ARCHIVE_EXTENSION = "zip";

    /** The media type of that archive. */
    public static final String ARCHIVE_MEDIA_TYPE = "application/zip";

    // SUPPRESS CHECKSTYLE ParameterNumber - a record's canonical constructor; the factory below is
    // what callers use, and every component is named by construction.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public OutputTarget {
        if (format == null) {
            throw new IllegalArgumentException("An OutputTarget needs a format");
        }
        if (sheets == null || sheets.isEmpty()) {
            throw new IllegalArgumentException(
                    "An OutputTarget for format " + format.id() + " receives no sheets");
        }
        sheets = List.copyOf(sheets);
        omittedSheets = omittedSheets == null ? List.of() : List.copyOf(omittedSheets);
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    /**
     * Works out what one format's file looks like for a given set of sheets.
     *
     * @param format      the format
     * @param allSheets   every sheet the definition declares, already resolved and narrowed to the
     *                    columns the requester may see
     * @param strategy    the definition's multi-sheet strategy
     * @param options     the request's options for this format
     * @param wantsTotals whether the definition asks for a totals row
     * @return the fully decided output
     */
    public static OutputTarget of(ReportFormat format, List<SheetSpec> allSheets,
                                  MultiSheetStrategy strategy, Map<String, String> options,
                                  boolean wantsTotals) {
        boolean needsContainer = allSheets.size() > 1 && !format.capabilities().multiSheet();
        List<SheetSpec> selected = allSheets;
        List<String> omitted = List.of();
        String extension = format.fileExtension();
        String mediaType = format.mediaType();

        if (needsContainer && strategy == MultiSheetStrategy.PRIMARY_ONLY) {
            SheetSpec primary = allSheets.stream().filter(SheetSpec::primary).findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "MultiSheetStrategy.PRIMARY_ONLY with no primary sheet; the registry"
                                    + " refuses that definition at startup"));
            selected = List.of(primary);
            omitted = allSheets.stream().filter(sheet -> !sheet.primary()).map(SheetSpec::id).toList();
        } else if (needsContainer && strategy == MultiSheetStrategy.ZIP) {
            // The extension has to change before the file is created, because the engine creates it:
            // a writer returning a differently-named file afterwards would leave two paths for the
            // engine to clean up, and at the design point one of them is four hundred megabytes.
            extension = ARCHIVE_EXTENSION;
            mediaType = ARCHIVE_MEDIA_TYPE;
        }

        return new OutputTarget(format, selected, omitted, strategy, extension, mediaType, options,
                wantsTotals && format.capabilities().totalsRow());
    }

    /** Whether this output receives the given sheet. */
    public boolean accepts(String sheetId) {
        return sheets.stream().anyMatch(sheet -> sheet.id().equals(sheetId));
    }
}
