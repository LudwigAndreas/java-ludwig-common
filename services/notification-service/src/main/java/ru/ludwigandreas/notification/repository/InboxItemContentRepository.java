package ru.ludwigandreas.notification.repository;

import java.util.UUID;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.notification.repository.entity.InboxItemContentEntity;

/**
 * Rendered inbox bodies, keyed by the item id itself.
 *
 * <p>Fetched deliberately and never as part of an item load, which is the point of holding it in its
 * own table: the list, the default filter and the unread count all scan the item row and none of them
 * wants a multi-kilobyte body alongside.
 */
public interface InboxItemContentRepository extends BaseRepository<InboxItemContentEntity, UUID> {
}
