package ru.ludwigandreas.notification.repository;

import java.util.UUID;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.notification.repository.entity.AnnouncementEntity;

/**
 * Announcements.
 *
 * <p>No derived query methods. Every read is either scoped by the caller's visibility or bounded by a
 * window, so the queries live in {@link AnnouncementQueryRepository} as QueryDSL against the
 * generated Q-type.
 */
public interface AnnouncementRepository
        extends BaseRepository<AnnouncementEntity, UUID>, AnnouncementQueryRepository {
}
