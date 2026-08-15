package ru.ludwigandreas.db.core.listener;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import ru.ludwigandreas.db.core.entity.SnapshotEntity;
import ru.ludwigandreas.db.core.exception.IntegrityViolationException;

import java.time.Instant;

public class SnapshotImmutabilityListener {

    @PrePersist
    void onPrePersist(SnapshotEntity<?> entity) {
        if (entity.getImportedAt() == null) {
            entity.setImportedAt(Instant.now());
        }
    }

    @PreUpdate
    void onPreUpdate(SnapshotEntity<?> entity) {
        throw new IntegrityViolationException(
                "Snapshot entities are immutable and cannot be updated after import: " + entity);
    }
}
