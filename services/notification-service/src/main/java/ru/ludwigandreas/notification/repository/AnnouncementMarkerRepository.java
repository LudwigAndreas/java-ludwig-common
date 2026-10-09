package ru.ludwigandreas.notification.repository;

import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.notification.repository.entity.AnnouncementMarkerEntity;
import ru.ludwigandreas.notification.repository.entity.AnnouncementMarkerId;

/**
 * Dismissal markers.
 *
 * <p>Keyed by the composite id, which makes "has this person dismissed this announcement" a
 * primary-key lookup and makes a repeated dismissal a constraint violation rather than a duplicate
 * row.
 */
public interface AnnouncementMarkerRepository
        extends BaseRepository<AnnouncementMarkerEntity, AnnouncementMarkerId> {
}
