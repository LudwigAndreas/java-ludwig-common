package ru.ludwigandreas.db.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;

/**
 * Root of the "outer" entity family: data whose id and lifecycle are owned by an external system (received
 * over REST or Kafka), as opposed to the "inner" family ({@link GeneratedEntity}, {@link AuditedEntity} and
 * friends) for data created by this application. {@code ID} is intentionally left fully generic and never
 * defaulted to {@link java.util.UUID} here, since an external system may hand back a {@code Long}, a
 * {@code String}, a {@code UUID}, or any other identifier type.
 * <p>
 * Carries provenance fields common to any externally-sourced record, whether it's kept mutable (a
 * "latest known copy" that's upserted in place on every fetch) or made immutable via
 * {@link SnapshotEntity} (one row per received version, kept forever). {@link #importedAt} and
 * {@link #sourceSystem} are set once at creation ({@code updatable = false}) and never change again;
 * {@link #sourceVersion}/{@link #sourceTimestamp} are free to change on every upsert for mutable
 * subclasses - it's {@link SnapshotEntity}'s blanket {@code @PreUpdate} rejection that makes a whole
 * snapshot row immutable, not these column constraints.
 */
@Getter
@Setter
@MappedSuperclass
public abstract class ExternalEntity<ID extends Serializable> extends JpaBaseEntity<ID> {

    @Column(nullable = false, updatable = false)
    private Instant importedAt;

    @Column(nullable = false, updatable = false)
    private String sourceSystem;

    private String sourceVersion;

    private Instant sourceTimestamp;

    @PrePersist
    void fillImportedAt() {
        if (importedAt == null) {
            importedAt = Instant.now();
        }
    }
}
