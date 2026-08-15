package ru.ludwigandreas.db.core.repository;

import jakarta.persistence.EntityManager;
import org.springframework.data.jpa.repository.support.JpaEntityInformation;
import org.springframework.data.jpa.repository.support.QuerydslJpaRepository;
import ru.ludwigandreas.db.core.entity.AbstractEntity;
import ru.ludwigandreas.db.core.exception.EntityNotFoundException;

import java.io.Serializable;

/**
 * Registered as the Spring Data repository base class via
 * {@code @EnableJpaRepositories(repositoryBaseClass = BaseRepositoryImpl.class)}, giving every
 * {@link BaseRepository} subinterface a real {@link BaseRepository#getByIdOrThrow(Serializable)}
 * implementation, backed by the {@link JpaEntityInformation} Spring Data already resolves per repository.
 */
public class BaseRepositoryImpl<T extends AbstractEntity<ID>, ID extends Serializable>
        extends QuerydslJpaRepository<T, ID>
        implements BaseRepository<T, ID> {

    private final JpaEntityInformation<T, ID> entityInformation;

    public BaseRepositoryImpl(JpaEntityInformation<T, ID> entityInformation, EntityManager entityManager) {
        super(entityInformation, entityManager);
        this.entityInformation = entityInformation;
    }

    @Override
    public T getByIdOrThrow(ID id) {
        return findById(id).orElseThrow(() -> new EntityNotFoundException(entityInformation.getJavaType(), id));
    }
}
