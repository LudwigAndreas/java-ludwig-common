package ru.ludwigandreas.export.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything a {@link ReportWriter} is given when it is created, once per run and format.
 *
 * <p>The writer is handed a temp file path rather than an {@code OutputStream} because two of the
 * three things this module needs from a writer are file-level: the engine has to be able to delete
 * a partial file on every exit path, and the sink has to be able to checksum and upload a completed
 * one. A stream would let a writer hold the only reference to a file the engine then could not
 * clean up after a failure - which at the design point is four hundred megabytes of orphan per
 * crash.
 *
 * <p>The sheets are named up front rather than discovered through {@link ReportWriter#beginSheet}
 * for the same class of reason: a writer that has to produce a container - CSV under
 * {@link MultiSheetStrategy#ZIP} - cannot decide to be an archive after the first sheet has already
 * been written. Knowing the plan before the first byte is what keeps that decision out of the
 * engine, where it would have to be made for every format that might ever need it.
 *
 * @param runId       the run, for the writer's own diagnostics and for the metadata sheet
 * @param format      the format being written, so a shared base class can consult its capabilities
 * @param targetFile  the temp file to write into. Created by the engine with owner-only permissions
 *                    and deleted by the engine on every exit path, including a JVM shutdown; a
 *                    writer must not create files of its own beside it
 * @param sheets      every sheet this writer will be asked for, in order, with resolved titles and
 *                    the columns that survived the requester's authorities
 * @param multiSheetStrategy what the definition asked for when a format has fewer sheets than it
 *                    declares. Already reconciled with this format's capabilities by the engine, so
 *                    a writer reads it rather than interpreting it
 * @param render      the run's locale and timezone
 * @param metadata    the run's provenance - definition key and version, requester, parameters,
 *                    filter, timestamps - with labels resolved and PII-flagged values already
 *                    redacted, in the order it is to be presented, for a format that can carry a
 *                    metadata sheet
 * @param template    the branding template this report is written into, or null for none. A name
 *                    rather than a file, because which file it resolves to is the template source's
 *                    decision and a writer that took a path could be pointed at one by a request
 * @param options     validated per-format options from the request, such as the CSV profile or
 *                    delimiter. Already checked against this format's declared option set, so a
 *                    writer may assume every key here is one it understands
 * @param totalsRow   whether this run writes a totals row; false when the format cannot carry one
 *                    or the request opted out
 */
public record WriterContext(
        UUID runId,
        ReportFormat format,
        Path targetFile,
        List<SheetSpec> sheets,
        MultiSheetStrategy multiSheetStrategy,
        RenderContext render,
        List<MetadataEntry> metadata,
        Map<String, String> options,
        boolean totalsRow,
        String template) {

    // SUPPRESS CHECKSTYLE ParameterNumber - a record's canonical constructor; every component is
    // named at the call site by construction, which is the readability the rule protects.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public WriterContext {
        if (runId == null) {
            throw new IllegalArgumentException("A WriterContext needs a run id");
        }
        if (format == null) {
            throw new IllegalArgumentException("A WriterContext needs a format");
        }
        if (targetFile == null) {
            throw new IllegalArgumentException("A WriterContext needs a target file");
        }
        if (sheets == null || sheets.isEmpty()) {
            throw new IllegalArgumentException("A WriterContext needs at least one sheet");
        }
        if (render == null) {
            throw new IllegalArgumentException("A WriterContext needs a render context");
        }
        sheets = List.copyOf(sheets);
        multiSheetStrategy = multiSheetStrategy == null ? MultiSheetStrategy.REJECT : multiSheetStrategy;
        metadata = metadata == null ? List.of() : List.copyOf(metadata);
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    /** An option's value, or the given fallback when the request did not set it. */
    public String option(String name, String fallback) {
        return options.getOrDefault(name, fallback);
    }

    /** Whether this writer has to carry more sheets than its format has. */
    public boolean needsSheetContainer() {
        return sheets.size() > 1 && !format.capabilities().multiSheet();
    }
}
