package ru.ludwigandreas.usersettings.repository;

import java.util.UUID;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.usersettings.entity.UserSettingAuditEntity;

/** The change trail. */
public interface UserSettingAuditRepository
        extends BaseRepository<UserSettingAuditEntity, UUID>, UserSettingAuditQueryRepository {
}
