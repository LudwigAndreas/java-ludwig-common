package ru.ludwigandreas.db.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import ru.ludwigandreas.db.core.listener.SnapshotImmutabilityListener;

import java.io.Serializable;
import java.time.Instant;

/**
 * An immutable point-in-time copy of data received from an outer system (via REST or Kafka). Once persisted,
 * a snapshot row cannot be updated: {@link SnapshotImmutabilityListener} rejects any {@code @PreUpdate} and
 * fills {@link #importedAt} automatically if it was left unset.
 */
@Getter
@Setter
@MappedSuperclass
@EntityListeners(SnapshotImmutabilityListener.class)
public abstract class SnapshotEntity<ID extends Serializable> extends ExternalEntity<ID> {

    @Column(nullable = false, updatable = false)
    private Instant importedAt;

    @Column(nullable = false, updatable = false)
    private String sourceSystem;

    private String sourceVersion;

    private Instant sourceTimestamp;
}
