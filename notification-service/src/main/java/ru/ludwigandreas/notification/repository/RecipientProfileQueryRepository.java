package ru.ludwigandreas.notification.repository;

import java.util.Optional;
import ru.ludwigandreas.notification.repository.entity.RecipientProfileEntity;

/** Profiles are only ever reached by the subject they describe. */
public interface RecipientProfileQueryRepository {

    Optional<RecipientProfileEntity> lookupByUserId(String userId);
}
