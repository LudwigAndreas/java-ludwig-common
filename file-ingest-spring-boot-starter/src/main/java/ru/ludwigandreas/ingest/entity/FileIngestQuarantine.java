package ru.ludwigandreas.ingest.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import ru.ludwigandreas.db.core.entity.GeneratedEntity;

/**
 * One record that could not be processed, and everything needed to do something about it.
 *
 * <h2>Poison records never abort and never vanish</h2>
 *
 * <p>A quarantined record that was only counted is a number an operator cannot act on. What makes a
 * quarantine row useful is the combination of three things: the record's own text, so somebody can
 * see what was wrong with it; its position, so it can be found in the source file; and the error, so
 * the reason does not have to be re-derived. A row missing any of the three sends whoever reads it
 * back to the original object with a line number they do not have.
 *
 * <p>The text is truncated at the task's configured cap. The record that failed is disproportionately
 * likely to be the enormous one - that is what a poison record <em>is</em> - and an untruncated
 * quarantine table would inherit exactly the memory problem the batch byte bound exists to prevent.
 */
@Getter
@Setter
@Entity
@Table(name = "file_ingest_quarantine")
public class FileIngestQuarantine extends GeneratedEntity<UUID> {

    /** The run that quarantined it. */
    @Column(name = "run_id", nullable = false)
    private UUID runId;

    /** The task, denormalised so a listing by task does not have to join. */
    @Column(name = "task", nullable = false, length = 128)
    private String task;

    /** The record's index in the object, counting from zero. */
    @Column(name = "record_ordinal", nullable = false)
    private long recordOrdinal;

    /** The byte offset of the record's first byte, for finding it in the source. */
    @Column(name = "byte_offset", nullable = false)
    private long byteOffset;

    /** Whether it failed to parse or failed to apply; the two point at different people. */
    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 32)
    private QuarantineStage stage;

    /** The record's own text, truncated at the task's cap. */
    @Column(name = "raw_record", length = 65536)
    private String rawRecord;

    /** What went wrong, including the exception type. */
    @Column(name = "error", nullable = false, length = 4000)
    private String error;

    /** When it happened. */
    @Column(name = "quarantined_at", nullable = false)
    private Instant quarantinedAt;
}
