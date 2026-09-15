package ru.ludwigandreas.identity.repository;

import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.identity.entity.SecurityUserEntity;

/**
 * Users are always reached by their OIDC subject, which is the primary key, so {@code findById} is the
 * whole read API - no derived query method and no JPQL string anywhere in this module.
 */
public interface SecurityUserRepository extends BaseRepository<SecurityUserEntity, String> {
}
