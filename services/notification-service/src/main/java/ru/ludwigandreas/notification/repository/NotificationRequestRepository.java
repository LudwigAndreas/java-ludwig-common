package ru.ludwigandreas.notification.repository;

import java.util.UUID;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.notification.repository.entity.NotificationRequestEntity;

/**
 * Requests are reached by id and nothing else - a duplicate is found through
 * {@code notification_idempotency}, not by scanning this table - so {@code JpaRepository}'s own
 * typed {@code findById} is the whole read API and no derived-query name appears anywhere.
 */
public interface NotificationRequestRepository extends BaseRepository<NotificationRequestEntity, UUID> {
}
