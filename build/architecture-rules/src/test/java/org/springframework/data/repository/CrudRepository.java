package org.springframework.data.repository;

import java.util.Optional;

/** Test stub of Spring Data's CrudRepository. */
public interface CrudRepository<T, ID> extends Repository<T, ID> {

    <S extends T> S save(S entity);

    Optional<T> findById(ID id);
}
