package ru.ludwigandreas.notification.repository;

import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.notification.repository.entity.AnnouncementContentEntity;
import ru.ludwigandreas.notification.repository.entity.AnnouncementContentId;

/**
 * Rendered announcement bodies, one per announcement per locale.
 *
 * <p>Keyed by the composite id, so fetching "this announcement in this language" is a primary-key
 * lookup rather than a query.
 */
public interface AnnouncementContentRepository
        extends BaseRepository<AnnouncementContentEntity, AnnouncementContentId> {
}
