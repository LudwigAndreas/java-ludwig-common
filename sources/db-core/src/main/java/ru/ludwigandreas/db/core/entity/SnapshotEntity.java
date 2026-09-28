package ru.ludwigandreas.db.core.entity;

import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import ru.ludwigandreas.db.core.listener.SnapshotImmutabilityListener;

import java.io.Serializable;

/**
 * An immutable point-in-time copy of data received from an outer system (via REST or Kafka): once
 * persisted, a snapshot row can never be updated - {@link SnapshotImmutabilityListener} rejects any
 * {@code @PreUpdate}. Provenance fields ({@code importedAt}, {@code sourceSystem}, {@code sourceVersion},
 * {@code sourceTimestamp}) live on {@link ExternalEntity}, shared with mutable "latest known copy" outer
 * entities; this class adds nothing but the immutability guarantee.
 */
@MappedSuperclass
@EntityListeners(SnapshotImmutabilityListener.class)
public abstract class SnapshotEntity<ID extends Serializable> extends ExternalEntity<ID> {
}
