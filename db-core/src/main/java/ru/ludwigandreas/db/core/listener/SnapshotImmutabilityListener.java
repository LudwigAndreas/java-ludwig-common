package ru.ludwigandreas.db.core.listener;

import jakarta.persistence.PreUpdate;
import org.springframework.beans.factory.annotation.Autowired;
import ru.ludwigandreas.db.core.entity.SnapshotEntity;
import ru.ludwigandreas.db.core.exception.IntegrityViolationException;
import ru.ludwigandreas.db.core.metrics.DbCoreMetrics;

/**
 * Instantiated by Hibernate (JPA entity listeners require a no-arg constructor), not by Spring - but
 * Spring Boot's Hibernate autoconfiguration registers a {@code SpringBeanContainer} by default
 * ({@code hibernate.resource.beans.container}), which autowires {@code @Autowired} fields on
 * listener/converter instances Hibernate creates. {@link #metrics} is therefore {@code required = false}
 * and null-checked: if that wiring isn't present for any reason (e.g. a non-Boot Hibernate setup), the
 * immutability check itself still works exactly as before, just without the metric.
 */
public class SnapshotImmutabilityListener {

    @Autowired(required = false)
    DbCoreMetrics metrics;

    @PreUpdate
    void onPreUpdate(SnapshotEntity<?> entity) {
        if (metrics != null) {
            metrics.recordSnapshotMutationBlocked(entity.getClass().getSimpleName());
        }
        throw new IntegrityViolationException(
                "Snapshot entities are immutable and cannot be updated after import: " + entity);
    }
}
