package ru.ludwigandreas.notification.repository;

import java.util.UUID;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.notification.repository.entity.DeliveryStatusHistoryEntity;

/**
 * Append-only. Reads go through {@link DeliveryQueryRepository#historyOf(UUID)} so that the ordering
 * the admin API depends on is stated once, in QueryDSL, rather than left to a caller to remember.
 */
public interface DeliveryStatusHistoryRepository extends BaseRepository<DeliveryStatusHistoryEntity, UUID> {
}
