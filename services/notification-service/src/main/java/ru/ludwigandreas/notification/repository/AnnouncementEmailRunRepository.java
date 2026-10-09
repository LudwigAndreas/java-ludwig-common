package ru.ludwigandreas.notification.repository;

import java.util.Optional;
import java.util.UUID;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.notification.repository.entity.AnnouncementEmailRunEntity;

/**
 * Email fan-out runs.
 *
 * <p>The queries live in {@link AnnouncementEmailRunQueryRepository} as QueryDSL; this interface adds
 * only the primary-key operations {@code BaseRepository} already gives.
 */
public interface AnnouncementEmailRunRepository
        extends BaseRepository<AnnouncementEmailRunEntity, UUID>,
        AnnouncementEmailRunQueryRepository {

    /**
     * The run for one announcement, of which there is at most one.
     *
     * <p>A derived query would be the obvious way to write this and is deliberately avoided - see
     * {@link AnnouncementEmailRunQueryRepository#findForAnnouncement}.
     */
    default Optional<AnnouncementEmailRunEntity> runFor(UUID announcementId) {
        return findForAnnouncement(announcementId);
    }
}
