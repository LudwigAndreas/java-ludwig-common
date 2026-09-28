package ru.ludwigandreas.usersettings.repository;

import java.util.UUID;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.usersettings.entity.UserSettingValueEntity;

/** Stored setting values, by id and through the QueryDSL fragment. */
public interface UserSettingValueRepository
        extends BaseRepository<UserSettingValueEntity, UUID>, UserSettingValueQueryRepository {
}
