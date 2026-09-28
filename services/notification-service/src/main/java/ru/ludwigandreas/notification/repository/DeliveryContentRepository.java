package ru.ludwigandreas.notification.repository;

import java.util.UUID;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.notification.repository.entity.DeliveryContentEntity;

/**
 * Rendered bodies, keyed by the delivery id itself. Always fetched deliberately and never as part of
 * a delivery load, which is the point of holding it in its own table.
 */
public interface DeliveryContentRepository extends BaseRepository<DeliveryContentEntity, UUID> {
}
