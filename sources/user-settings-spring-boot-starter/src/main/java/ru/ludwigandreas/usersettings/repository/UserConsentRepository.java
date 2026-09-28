package ru.ludwigandreas.usersettings.repository;

import java.util.UUID;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.usersettings.entity.UserConsentEntity;

/**
 * The consent ledger.
 *
 * <p>Inherits {@code delete} from {@code JpaRepository} and no code in this module calls it. That is
 * not an oversight to be fixed by narrowing the interface: the immutability guarantee that matters
 * is the one the database and the entity listener enforce, not one expressed by which methods a Java
 * interface happens to expose - a retention purge has to be able to delete rows eventually, and an
 * interface that made deletion impossible would simply be worked around.
 */
public interface UserConsentRepository
        extends BaseRepository<UserConsentEntity, UUID>, UserConsentQueryRepository {
}
