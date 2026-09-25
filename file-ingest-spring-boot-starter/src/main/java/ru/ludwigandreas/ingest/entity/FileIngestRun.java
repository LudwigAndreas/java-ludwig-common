package ru.ludwigandreas.ingest.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import ru.ludwigandreas.db.core.entity.GeneratedEntity;
import ru.ludwigandreas.ingest.api.CheckpointKind;
import ru.ludwigandreas.ingest.api.IngestRunStatus;

/**
 * One attempt to ingest one object.
 *
 * <h2>The unique constraint is the exactly-once guard</h2>
 *
 * <p>{@code (container, object_key, content_identity)} is unique, and that constraint - not a
 * {@code processed} flag somebody remembers to check - is what makes an object ingested once. The
 * difference is not stylistic. A flag is read, then acted on, and two replicas that read it in the
 * same moment both act; a unique constraint is enforced by the database at the moment of the insert,
 * so the second replica takes a violation and stands down.
 *
 * <p>The third column of the triple is what keeps it honest. Keying on the object key alone means a
 * partner re-uploading a <em>corrected</em> file under the same name is recognised as a duplicate and
 * skipped: silent data loss, discovered weeks later when somebody asks why the correction never
 * landed. Content identity is the etag, or the version id on a versioned bucket - see
 * {@code StoredObject#contentIdentity()} for why the version is preferred where there is one.
 *
 * <h2>Why the counts live here and not only in a summary</h2>
 *
 * <p>Because the balance check has to be answerable after a crash. A run that died between its last
 * batch and its completion has to be resumable, and resuming means knowing how many records the
 * committed checkpoint accounted for - which is the same number the balance check needs. Keeping them
 * on the row makes both facts survive the process that produced them.
 */
@Getter
@Setter
@Entity
@Table(name = "file_ingest_run",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_file_ingest_run_identity",
                columnNames = {"container", "object_key", "content_identity"}))
public class FileIngestRun extends GeneratedEntity<UUID> {

    /** The task this run belongs to. */
    @Column(name = "task", nullable = false, length = 128)
    private String task;

    /** The bucket, or the empty string for a filesystem store. */
    @Column(name = "container", nullable = false, length = 255)
    private String container;

    /** The object's key within the container. */
    @Column(name = "object_key", nullable = false, length = 1024)
    private String objectKey;

    /**
     * The etag or version the run is keyed on.
     *
     * <p>The third column of the identity triple. Never null: a store that reported no identity at all
     * would make every re-offer a new run, which is a worse failure than the one the triple prevents.
     */
    @Column(name = "content_identity", nullable = false, length = 255)
    private String contentIdentity;

    /** The full location, kept whole so a log line or an actuator response can show it. */
    @Column(name = "source_uri", nullable = false, length = 2048)
    private String sourceUri;

    /** Where the run is. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private IngestRunStatus status;

    /** Which checkpoint shape the parser produces, so a resume does not have to ask the bean. */
    @Enumerated(EnumType.STRING)
    @Column(name = "checkpoint_kind", nullable = false, length = 32)
    private CheckpointKind checkpointKind;

    /**
     * The committed position: a byte offset or a record ordinal, per {@link #checkpointKind}.
     *
     * <p>Advanced in the same transaction as the batch it describes. That is the module's central
     * invariant, and the reason this column is on the run row rather than in a separate progress table
     * - a second table is a second transaction waiting to happen.
     */
    @Column(name = "checkpoint_position", nullable = false)
    private long checkpointPosition;

    /** How many records the committed checkpoint accounts for. */
    @Column(name = "records_committed", nullable = false)
    private long recordsCommitted;

    /** Uncompressed bytes the parser has consumed. */
    @Column(name = "bytes_read", nullable = false)
    private long bytesRead;

    /** Records the parser produced, good and bad. */
    @Column(name = "records_read", nullable = false)
    private long recordsRead;

    /** Records the applier wrote into staging. */
    @Column(name = "records_applied", nullable = false)
    private long recordsApplied;

    /** Records that failed to parse or to apply. */
    @Column(name = "records_quarantined", nullable = false)
    private long recordsQuarantined;

    /** Records the applier itself collapsed, which the balance check accounts for. */
    @Column(name = "records_skipped", nullable = false)
    private long recordsSkipped;

    /** The record count the sentinel declared, or null when it declared none. */
    @Column(name = "expected_records")
    private Long expectedRecords;

    /** Which instance is running it, for an operator looking at a stuck run. */
    @Column(name = "locked_by", length = 255)
    private String lockedBy;

    /** When the object was claimed. */
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /** When it reached a terminal status. */
    @Column(name = "finished_at")
    private Instant finishedAt;

    /** Why it failed, for a run that did; the balance numbers when that is the reason. */
    @Column(name = "failure_message", length = 4000)
    private String failureMessage;

    /** Whether the receipt object was written, so a re-run does not have to guess. */
    @Column(name = "receipt_written", nullable = false)
    private boolean receiptWritten;

    /** Whether the source was archived, for the same reason. */
    @Column(name = "archived", nullable = false)
    private boolean archived;

    /**
     * Optimistic lock.
     *
     * <p>Not the exactly-once guard - the unique constraint is - but what stops two threads inside one
     * instance from advancing the same checkpoint concurrently, which the scheduler alone does not
     * prevent if somebody triggers a manual run while a scheduled one is in flight.
     */
    @jakarta.persistence.Version
    @Column(name = "version", nullable = false)
    private long version;
}
