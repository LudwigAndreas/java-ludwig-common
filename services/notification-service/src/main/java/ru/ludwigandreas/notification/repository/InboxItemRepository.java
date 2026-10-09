package ru.ludwigandreas.notification.repository;

import java.util.UUID;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.notification.repository.entity.InboxItemEntity;

/**
 * The recipient-owned inbox items.
 *
 * <p>Deliberately carries no derived query methods. Every read of this table is scoped to exactly one
 * owner and most carry a caller-supplied filter, so the queries live in
 * {@link InboxQueryRepository} as QueryDSL against the generated Q-type - which is this
 * repository's standing rule and not an exception made here.
 */
public interface InboxItemRepository
        extends BaseRepository<InboxItemEntity, UUID>, InboxQueryRepository {
}
