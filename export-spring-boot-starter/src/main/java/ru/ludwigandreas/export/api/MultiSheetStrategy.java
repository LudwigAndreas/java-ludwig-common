package ru.ludwigandreas.export.api;

/**
 * What a definition that declares several sheets does when it is asked for in a format that has one.
 *
 * <p>The option this enum deliberately does not have is "write the first sheet and say nothing".
 * That is the behaviour every exporter grows by accident, and it is the worst of the three: the
 * requester gets a file, the file opens, and it is missing most of the report with no indication
 * anywhere that anything was left out.
 */
public enum MultiSheetStrategy {

    /**
     * Refuse the combination. The default: a definition with several sheets is a document with
     * several parts, and a requester who asked for it as CSV should be told the format cannot carry
     * it rather than handed a part of it.
     */
    REJECT,

    /** One file per sheet, delivered as a single archive. The full report, in a format that has no sheets. */
    ZIP,

    /**
     * Write only the sheet the definition marks primary, and record the omission.
     *
     * <p>Legal because it is sometimes genuinely what the requester wants - a machine consuming the
     * data table and ignoring the summary - but the omitted sheets are listed on the run record and
     * reported by the download response, so nobody is told a partial file is the whole one.
     */
    PRIMARY_ONLY
}
