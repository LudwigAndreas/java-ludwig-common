package ru.ludwigandreas.fileaction.api;

/** What a submission's downloadable reject report is, when it has one. */
public enum ErrorReportFormat {

    /**
     * No report. The paged rejects endpoint still serves the bounded sample.
     *
     * <p>Right for a machine caller, which reads the rejects as data and has no use for a file.
     */
    NONE,

    /**
     * A semicolon-delimited, byte-order-marked CSV of the rejects.
     *
     * <p>The sensible choice when the submission itself was a CSV: annotating a CSV means adding a field to
     * every row, which changes the shape of the file the user would re-submit.
     */
    CSV,

    /**
     * The submitted workbook, copied, with a column saying what is wrong with each row.
     *
     * <p>The best report there is for a person: they fix the rows in their own file, in their own columns, and
     * re-upload it as it stands. Only available when the submission was a workbook; a CSV submission falls
     * back to {@link #CSV}, because there is no workbook to annotate.
     */
    ANNOTATED_WORKBOOK
}
