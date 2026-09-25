package ru.ludwigandreas.export.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import ru.ludwigandreas.db.core.entity.GeneratedEntity;
import ru.ludwigandreas.export.api.RunStatus;

/**
 * A row in {@code export_report_run}: one request to produce a report, and everything that happened
 * to it.
 *
 * <h2>Why the request is stored and not only referenced</h2>
 *
 * <p>The parameters, the filter, the column subset and the formats are all stored on the row rather
 * than reconstructed from a saved configuration at execution time. A saved configuration is editable;
 * a run is a record of what was actually asked for. Without the snapshot, "why does this file have
 * these columns" becomes unanswerable the moment an administrator edits the configuration, which is
 * precisely the question an auditor asks about the file they are holding.
 *
 * <p>The principal snapshot is stored for the same reason and used for none of the same things: it
 * says who asked, and it is <em>not</em> what the run executes under. A deferred run re-resolves the
 * requester's authorities when it starts, so a revocation between request and execution takes
 * effect - see {@code ReportAccessRevokedException}.
 *
 * <h2>The lifecycle columns</h2>
 *
 * <p>{@code claimed_by}, {@code claimed_at}, {@code heartbeat_at} and {@code lease_until} are
 * {@code job-core}'s lease, written by the claim statement and renewed while the run is alive. An
 * instance that dies stops renewing, the lease expires, and the row becomes claimable again - which
 * is the whole reason a run in {@code RUNNING} is not stuck forever.
 */
@Getter
@Setter
@Entity
@Table(name = "export_report_run")
@EntityListeners(AuditingEntityListener.class)
public class ExportReportRun extends GeneratedEntity<UUID> {

    @Column(name = "definition_key", nullable = false, updatable = false, length = 128)
    private String definitionKey;

    /** The definition's declared version when the run was accepted; see {@code ReportDefinition}. */
    @Column(name = "definition_version", nullable = false, updatable = false)
    private int definitionVersion;

    @Column(name = "saved_report_id", updatable = false)
    private UUID savedReportId;

    @Column(name = "requester", nullable = false, updatable = false, length = 255)
    private String requester;

    /** Who asked, as they were when they asked. Never what the run executes under. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "principal_snapshot", updatable = false, columnDefinition = "jsonb")
    private Map<String, String> principalSnapshot;

    /** The parameters as submitted, with PII-flagged values redacted before they were written. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parameters", updatable = false, columnDefinition = "jsonb")
    private Map<String, String> parameters;

    @Column(name = "filter_expression", updatable = false, columnDefinition = "text")
    private String filterExpression;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "column_ids", updatable = false, columnDefinition = "jsonb")
    private List<String> columnIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "formats", nullable = false, updatable = false, columnDefinition = "jsonb")
    private List<String> formats;

    @Column(name = "locale", nullable = false, updatable = false, length = 35)
    private String locale;

    @Column(name = "time_zone", nullable = false, updatable = false, length = 64)
    private String timeZone;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RunStatus status = RunStatus.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    /**
     * When this run becomes claimable again.
     *
     * <p>The poller's due predicate reads this, so a retry's backoff is expressed as a timestamp
     * rather than as a sleep somewhere: the delay survives a restart, and an instance that comes back
     * up does not re-run everything that was waiting.
     */
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    /**
     * Somebody asked this run to stop.
     *
     * <p>A column of its own rather than a status, because the run is still RUNNING until the
     * instance executing it notices - and that instance may not be the one that took the request. A
     * status flipped to CANCELLED by a second instance would make the executing one's next write
     * resurrect it.
     */
    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    @Column(name = "claimed_by", length = 255)
    private String claimedBy;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "rows_written", nullable = false)
    private long rowsWritten;

    /** How far along the run is, for a progress endpoint; 0-100, never interpolated by the client. */
    @Column(name = "percent", nullable = false)
    private int percent;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** The problem code this run failed with, so a client sees the same code a synchronous run gives. */
    @Column(name = "failure_code", length = 128)
    private String failureCode;

    /** Message key plus its arguments, never a formatted sentence; resolved at the edge, per caller. */
    @Column(name = "failure_message_key", length = 128)
    private String failureMessageKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "failure_arguments", columnDefinition = "jsonb")
    private List<String> failureArguments;

    /** Enrichment stages that were degraded rather than failing the run. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "degraded_stages", columnDefinition = "jsonb")
    private List<String> degradedStages;

    /** Sheets left out of at least one output, under {@code MultiSheetStrategy.PRIMARY_ONLY}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "omitted_sheets", columnDefinition = "jsonb")
    private List<String> omittedSheets;

    @Column(name = "correlation_id", updatable = false, length = 64)
    private String correlationId;

    /**
     * The hash of the request, or a key the caller supplied.
     *
     * <p>Unique, and load-bearing: a retry restarts a run from scratch rather than resuming it, which
     * is only safe because the same request produces the same file. This column is where that claim
     * is enforced - a second run under the same key is refused rather than queued, so a client
     * retrying a timed-out HTTP call does not put a second million-row report behind the first.
     */
    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
    private String idempotencyKey;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false, length = 255)
    private String createdBy;

    /** Whether this run is finished, one way or another. */
    public boolean isTerminal() {
        return status == RunStatus.SUCCEEDED || status == RunStatus.FAILED
                || status == RunStatus.CANCELLED || status == RunStatus.EXPIRED;
    }
}
