package ru.ludwigandreas.export.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the platform is told when a report is ready.
 *
 * <h2>A link, never the file</h2>
 *
 * <p>The payload carries a download reference and the file's size and checksum - not its bytes, and
 * not an attachment. A forty-megabyte XLSX on a message bus is a message half the consumers will
 * reject, and the half that accept it will hand it to a mail server that will not. The recipient
 * follows the link, which also means the download goes through the same authorisation check as any
 * other - an attachment would be a copy of the data with the access control left behind.
 *
 * <p>It is the only way the expiry can mean anything, too: a file that has been purged stops being
 * downloadable, where a copy that was emailed out is forever.
 *
 * @param runId          the run that produced it
 * @param definitionKey  which report
 * @param savedReportId  the configuration it came from, or null for an ad-hoc run
 * @param requester      who asked
 * @param recipients     who should be told, for a subscription; empty for an ad-hoc run
 * @param outputs        one entry per format produced
 * @param rowsWritten    how many rows reached a file
 * @param degradedStages enrichment stages that were incomplete, so a consumer can say so
 * @param completedAt    when it finished
 * @param expiresAt      when the files stop being downloadable
 */
public record ReportReadyEvent(
        UUID runId,
        String definitionKey,
        UUID savedReportId,
        String requester,
        List<String> recipients,
        List<ReadyOutput> outputs,
        long rowsWritten,
        List<String> degradedStages,
        Instant completedAt,
        Instant expiresAt) {

    /** The event type, part of the contract every consumer routes on. */
    public static final String EVENT_TYPE = "report.ready";

    /** The aggregate type, so a consumer can subscribe to reports rather than to everything. */
    public static final String AGGREGATE_TYPE = "ExportReportRun";

    // SUPPRESS CHECKSTYLE ParameterNumber - a record's canonical constructor; every component is
    // named at the call site by construction, which is the readability the rule protects.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public ReportReadyEvent {
        recipients = recipients == null ? List.of() : List.copyOf(recipients);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        degradedStages = degradedStages == null ? List.of() : List.copyOf(degradedStages);
    }

    /**
     * One produced file, as a consumer sees it.
     *
     * @param formatId  which format
     * @param fileName  the name to present to whoever downloads it
     * @param mediaType what it is
     * @param sizeBytes how big, so a consumer can decide whether to offer it at all
     * @param sha256    what it hashed to, so a download can be verified months later
     */
    public record ReadyOutput(String formatId, String fileName, String mediaType, long sizeBytes,
                              String sha256) {
    }
}
