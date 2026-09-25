package ru.ludwigandreas.export.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One thing that happened to a run, recorded for the trail rather than for the operator.
 *
 * <p>Reports are the most common data-exfiltration path in an enterprise estate: they are designed
 * to take a large amount of data out of a system in a portable file, and they are normally
 * available to a wider population than any other bulk read. The trail of who exported what is
 * therefore not an optional feature of this module, which is why every lifecycle transition emits
 * one of these and why the sink interface has no "enabled" flag of its own.
 *
 * <p>Parameters arrive here with PII-flagged values already redacted, and column ids rather than
 * column values throughout. What the trail answers is which definition, which configuration, which
 * columns, how many rows and where the file went - never what was in it.
 *
 * @param runId          the run
 * @param status         the status it moved into
 * @param at             when, from the injected clock rather than from {@code Instant.now()}
 * @param definitionKey  which report
 * @param savedReportId  the saved configuration this run came from, or null for an ad-hoc run
 * @param principalId    who asked
 * @param parameters     the parameters as submitted, PII-redacted
 * @param columnIds      the columns actually written, after visibility was applied
 * @param formats        the format ids produced
 * @param rowsWritten    how many rows reached a file
 * @param outputUris     where the files went
 * @param degradedStages enrichment stages that failed and were degraded rather than failing the run
 * @param correlationId  ties this event to the run's logs and to its partner calls
 */
public record ExportAuditEvent(
        UUID runId,
        RunStatus status,
        Instant at,
        String definitionKey,
        UUID savedReportId,
        String principalId,
        Map<String, String> parameters,
        List<String> columnIds,
        List<String> formats,
        long rowsWritten,
        List<String> outputUris,
        List<String> degradedStages,
        String correlationId) {

    // SUPPRESS CHECKSTYLE ParameterNumber - a record's canonical constructor. An audit event is a
    // wide record by nature; every component is named at the call site by construction.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public ExportAuditEvent {
        if (runId == null) {
            throw new IllegalArgumentException("An ExportAuditEvent needs a run id");
        }
        if (status == null) {
            throw new IllegalArgumentException("An ExportAuditEvent needs a status");
        }
        if (at == null) {
            throw new IllegalArgumentException("An ExportAuditEvent needs a timestamp");
        }
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        columnIds = columnIds == null ? List.of() : List.copyOf(columnIds);
        formats = formats == null ? List.of() : List.copyOf(formats);
        outputUris = outputUris == null ? List.of() : List.copyOf(outputUris);
        degradedStages = degradedStages == null ? List.of() : List.copyOf(degradedStages);
    }
}
