package ru.ludwigandreas.db.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.data.repository.NoRepositoryBean;
import ru.ludwigandreas.db.core.entity.AbstractEntity;
import ru.ludwigandreas.db.core.exception.EntityNotFoundException;

import java.io.Serializable;

/**
 * Repository base combining {@link JpaRepository} and {@link QuerydslPredicateExecutor}, plus a
 * {@link #getByIdOrThrow(Serializable)} convenience.
 * <p>
 * Bound to {@link AbstractEntity} (rather than {@link ru.ludwigandreas.db.core.entity.JpaBaseEntity}) since
 * that's the common root shared by both the plain-assigned-id family and the generated-id family
 * ({@link ru.ludwigandreas.db.core.entity.GeneratedEntity}/{@link ru.ludwigandreas.db.core.entity.AuditedEntity}).
 * <p>
 * To use it, configure {@link BaseRepositoryImpl} as the Spring Data repository base class:
 * <pre>{@code
 * @EnableJpaRepositories(repositoryBaseClass = BaseRepositoryImpl.class)
 * }</pre>
 */
@NoRepositoryBean
public interface BaseRepository<T extends AbstractEntity<ID>, ID extends Serializable>
        extends JpaRepository<T, ID>, QuerydslPredicateExecutor<T> {

    /**
     * @throws EntityNotFoundException if no entity with the given id exists
     */
    T getByIdOrThrow(ID id);
}
