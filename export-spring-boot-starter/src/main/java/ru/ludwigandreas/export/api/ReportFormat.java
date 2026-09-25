package ru.ludwigandreas.export.api;

/**
 * A file format a report can be produced in.
 *
 * <p>Deliberately open, unlike {@link Enricher} and {@link CellValue}. Those are sealed because the
 * engine switches over them and a shape it does not know would fall through to no behaviour; a
 * format is the opposite situation - it is dispatched to by identity through
 * {@link ReportWriterFactory}, and the engine never needs to enumerate the set. Adding PDF or ODS is
 * a new factory bean and no change here, which is the whole point of the seam: those two are out of
 * scope for this module and must not require reopening it.
 *
 * <p>Implementations are values: they are compared by {@link #id()}, held in maps, and stored in the
 * run record. An enum constant is the usual implementation, and {@link StandardReportFormats} holds
 * the two this module ships.
 */
public interface ReportFormat {

    /**
     * Stable identifier, lowercase. Stored on the run record, named in requests and in
     * {@code ludwig.export.formats.enabled}, so it is part of the API contract.
     *
     * @return the id
     */
    String id();

    /**
     * The media type the download endpoint reports.
     *
     * @return an RFC 6838 media type
     */
    String mediaType();

    /**
     * The file extension, without a leading dot.
     *
     * @return the extension
     */
    String fileExtension();

    /**
     * What this format can carry.
     *
     * @return the capabilities, checked at startup, at request time and at write time
     */
    FormatCapabilities capabilities();
}
