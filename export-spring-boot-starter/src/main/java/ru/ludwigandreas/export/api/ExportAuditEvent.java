package ru.ludwigandreas.export.api;

import java.time.Instant;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ru.ludwigandreas.audit.Actor;
import ru.ludwigandreas.audit.AuditCategories;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditOutcome;
import ru.ludwigandreas.audit.Resource;

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
 * <p>Kept as this module's authoring surface after the audit consolidation rather than replaced by
 * {@link AuditEvent}: thirteen components assembled positionally at a call site are readable, and
 * thirteen entries put into a {@code Map<String, Object>} are not. {@link #toAuditEvent()} flattens it
 * into the platform envelope, which is the transport and storage surface.
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

    /** The resource type every export event is about. */
    public static final String RESOURCE_TYPE = "report-run";

    /**
     * This event as a platform audit event.
     *
     * <p>The action is the status the run moved into, lower-cased under a {@code run.} prefix, so
     * {@code run.succeeded} and {@code run.failed} are what a SIEM rule and a query branch on rather than
     * a status column whose values come from this module's enum.
     *
     * <p>A run that degraded an enrichment stage is {@link AuditOutcome.Status#PARTIAL} and not a success.
     * The old record carried {@code degradedStages} and the old log line printed it, which meant the
     * difference between a complete report and one missing a column was a field somebody had to notice. It
     * is now the outcome.
     *
     * @return the event, with parameters already PII-redacted and no column values anywhere
     */
    public AuditEvent toAuditEvent() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("definitionKey", definitionKey);
        attributes.put("savedReportId", savedReportId == null ? null : savedReportId.toString());
        attributes.put("status", status == null ? null : status.name());
        attributes.put("rowsWritten", rowsWritten);
        if (!formats.isEmpty()) {
            attributes.put("formats", formats);
        }
        if (!columnIds.isEmpty()) {
            attributes.put("columnIds", columnIds);
        }
        if (!outputUris.isEmpty()) {
            attributes.put("outputUris", outputUris);
        }
        if (!degradedStages.isEmpty()) {
            attributes.put("degradedStages", degradedStages);
        }
        if (!parameters.isEmpty()) {
            attributes.put("parameters", parameters);
        }
        return AuditEvent.builder()
                .category(AuditCategories.EXPORT)
                .action("run." + status.name().toLowerCase(Locale.ROOT))
                .occurredAt(at)
                .actor(principalId == null ? Actor.system() : Actor.of(principalId))
                .resource(new Resource(RESOURCE_TYPE, runId.toString(), definitionKey))
                .outcome(outcomeOf())
                .correlationId(correlationId)
                .attributes(attributes)
                .build();
    }

    private AuditOutcome outcomeOf() {
        if (status == RunStatus.FAILED) {
            return AuditOutcome.failure(null);
        }
        if (!degradedStages.isEmpty()) {
            return AuditOutcome.partial("degraded stages: " + String.join(", ", degradedStages));
        }
        return AuditOutcome.success();
    }
}
