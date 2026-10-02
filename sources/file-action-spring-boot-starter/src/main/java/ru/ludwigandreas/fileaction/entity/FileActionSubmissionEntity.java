package ru.ludwigandreas.fileaction.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.ludwigandreas.db.core.entity.GeneratedEntity;
import ru.ludwigandreas.fileaction.api.FileActionState;
import ru.ludwigandreas.fileaction.format.SourceFormat;

/**
 * One user-submitted file and what became of it.
 *
 * <h2>The row exists before anything parses the file</h2>
 *
 * <p>Written as soon as the bytes are stored and the claim is won, so that a submission which fails during
 * the read is still a record of what was uploaded, by whom and when. That is the question asked three days
 * later, when somebody says the file had four hundred rows and the system says it had three hundred and
 * ninety-eight - and it is unanswerable if the row is only written on success.
 *
 * <h2>Why this is not a shared operation row</h2>
 *
 * <p>The {@code long-running-operations} capability says there is no shared operation table, and this table
 * is the reason the rule reads the way it does: half these columns are about a <em>file</em> - a content
 * hash, a sheet, a source format, four row counts, two artifact URIs - and a generic operation table would
 * either not have them or would have them for every module that has no file. The envelope is produced from
 * this row at the module's edge, by {@code SubmissionResponses}, which is where the mapping belongs.
 */
@Entity
@Table(name = "file_action_submission")
@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
// SUPPRESS CHECKSTYLE ID TooManyFields - a submission genuinely has this many facts about it, and every
// one of them is read by the envelope, the reject report, the retention job or the audit record. Splitting
// it would produce a one-to-one join that is written and read together every time.
public class FileActionSubmissionEntity extends GeneratedEntity<UUID> {

    /** The configured action this submission belongs to. */
    @Column(name = "action", nullable = false, length = 128, updatable = false)
    private String action;

    /** Where in this module's lifecycle it is. */
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    private FileActionState state;

    /**
     * The stored object holding the submitted bytes.
     *
     * <p>A full {@code s3://} or {@code file://} URI rather than a bucket and a key, because
     * {@code ObjectUri} is the one place that form is parsed and splitting it here would be a second place
     * - which is exactly how two components come to disagree about whether a key may contain a slash.
     */
    @Column(name = "object_uri", nullable = false, length = 1024)
    private String objectUri;

    /** The content hash, which is also this submission's idempotency key. */
    @Column(name = "content_sha256", nullable = false, length = 64, updatable = false)
    private String contentSha256;

    /** The submitted size in bytes. */
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** The name the client sent. For messages and the audit record only. */
    @Column(name = "declared_filename", length = 512)
    private String declaredFilename;

    /** The content type the client declared. Never used to choose a parser; see {@code FormatSniffer}. */
    @Column(name = "declared_content_type", length = 255)
    private String declaredContentType;

    /** What the content actually turned out to be. */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_format", length = 16)
    private SourceFormat sourceFormat;

    /** The sheet that was read, or null for a CSV. */
    @Column(name = "sheet", length = 255)
    private String sheet;

    /**
     * Who uploaded it.
     *
     * <p>A subject string, not a foreign key. Identity is owned by {@code identity-provider-service}, and a
     * foreign key here would be a local copy of it that goes stale the first time somebody is renamed.
     */
    @Column(name = "submitted_by", length = 255, updatable = false)
    private String submittedBy;

    /** When it was accepted. */
    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    /** When the read or the apply first started, or null while nothing has. */
    @Column(name = "started_at")
    private Instant startedAt;

    /** When it reached a terminal state, or null while it has not. */
    @Column(name = "finished_at")
    private Instant finishedAt;

    /**
     * After this moment the submission's stored artifacts are gone.
     *
     * <p>One column for two things that are the same question: when a {@code VALIDATED} submission stops
     * being confirmable, and when a terminal one's stored bytes become collectable. Two columns would need a
     * rule about which wins, and the rule would be read differently by the confirm path and the retention
     * job.
     */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /** The submitting caller's locale tag, so a report produced later renders in their language. */
    @Column(name = "locale", length = 35)
    private String locale;

    /** The submitting caller's zone id, so a date coerces as they meant it even on a worker thread. */
    @Column(name = "zone", length = 64)
    private String zone;

    /** The correlation id of the request that submitted it. */
    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    /** How many data rows were read. */
    @Column(name = "rows_read", nullable = false)
    private long rowsRead;

    /** How many rows the handler applied. */
    @Column(name = "rows_applied", nullable = false)
    private long rowsApplied;

    /** How many rows were refused. Counts toward the reject threshold. */
    @Column(name = "rows_rejected", nullable = false)
    private long rowsRejected;

    /** How many rows the handler deliberately ignored. Does not count toward the threshold. */
    @Column(name = "rows_skipped", nullable = false)
    private long rowsSkipped;

    /** Why a {@code REJECTED} submission was refused: a message code, never a formatted message. */
    @Column(name = "failure_code", length = 128)
    private String failureCode;

    /** The failure message's arguments, newline-separated. */
    @Column(name = "failure_args", columnDefinition = "text")
    private String failureArgs;

    /** The bound-row artifact a {@code CONFIRM}-mode submission applies. */
    @Column(name = "bound_rows_uri", length = 1024)
    private String boundRowsUri;

    /** The reject report artifact, when one was produced. */
    @Column(name = "error_report_uri", length = 1024)
    private String errorReportUri;

    /** Which instance holds the deferred claim, or null when nobody does. */
    @Column(name = "locked_by", length = 255)
    private String lockedBy;

    /** When the deferred claim lapses, so a dead holder's submission is reclaimable. */
    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    /** How many times the apply has been attempted. */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    /**
     * Whether somebody has asked this submission to stop.
     *
     * <p>A flag the running apply checks between batches, not an interrupt. This column is the reason a
     * cancel endpoint answers 202 rather than 204: setting it requests the stop and does not achieve it.
     */
    @Column(name = "cancellation_requested", nullable = false)
    private boolean cancellationRequested;

    /** When the row was created. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** When the row last changed. */
    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * The optimistic-locking version.
     *
     * <p>Load-bearing rather than decorative: a cancel request and the apply that is running concurrently
     * both write this row, and without a version the cancel's write is lost - the submission finishes as
     * though nobody had asked it to stop, which is the one outcome a cancel endpoint must not produce.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** Whether this submission is in a state nothing further will happen to. */
    public boolean isTerminal() {
        return state != null && state.isTerminal();
    }
}
