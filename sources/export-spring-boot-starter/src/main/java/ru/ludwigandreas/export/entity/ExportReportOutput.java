package ru.ludwigandreas.export.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import ru.ludwigandreas.db.core.entity.GeneratedEntity;

/**
 * A row in {@code export_report_output}: one file a run produced.
 *
 * <p>One row per format rather than a set of columns on the run, because a multi-format run produces
 * genuinely independent files: each has its own size, its own checksum, its own sink uri and its own
 * expiry, and each is downloaded separately. Folding them onto the run would mean a column per format
 * and a schema change for every format anyone ever adds.
 *
 * <p>The checksum is here rather than only in the sink because it answers a question about the past:
 * months later, is the file being discussed byte-for-byte the file this run produced? A sink that has
 * since been migrated, or a file that was copied somewhere, cannot answer that; a stored hash can.
 *
 * <p>{@code expires_at} is written when the file is stored, not computed from the retention setting
 * at purge time. Shortening the retention should not retroactively expire files somebody was told
 * they had a week to download.
 */
@Getter
@Setter
@Entity
@Table(name = "export_report_output")
@EntityListeners(AuditingEntityListener.class)
public class ExportReportOutput extends GeneratedEntity<UUID> {

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "format_id", nullable = false, updatable = false, length = 32)
    private String formatId;

    /** Opaque to this module; only the sink that wrote it knows how to read it back. */
    @Column(name = "sink_uri", nullable = false, updatable = false, columnDefinition = "text")
    private String sinkUri;

    @Column(name = "file_name", nullable = false, updatable = false, length = 255)
    private String fileName;

    @Column(name = "media_type", nullable = false, updatable = false, length = 128)
    private String mediaType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    /** Lowercase hex SHA-256 of the stored bytes. */
    @Column(name = "sha256", nullable = false, updatable = false, length = 64)
    private String sha256;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** When the purge removed the file. The row outlives the bytes, so the trail survives. */
    @Column(name = "purged_at")
    private Instant purgedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Whether the bytes are still there. */
    public boolean isDownloadable() {
        return purgedAt == null;
    }
}
