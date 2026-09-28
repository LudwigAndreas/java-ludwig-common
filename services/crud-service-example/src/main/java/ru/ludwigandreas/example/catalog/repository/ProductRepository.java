package ru.ludwigandreas.example.catalog.repository;

import java.util.UUID;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.example.catalog.repository.entity.ProductEntity;

/**
 * db-core's {@code BaseRepository} contributes {@code JpaRepository} +
 * {@code QuerydslPredicateExecutor} + {@code getByIdOrThrow}; {@link ProductQueryRepository}
 * contributes this service's own QueryDSL queries. No query is declared on this interface itself -
 * there is no derived-query method name and no {@code @Query} string anywhere in this service.
 */
public interface ProductRepository extends BaseRepository<ProductEntity, UUID>, ProductQueryRepository {
}
