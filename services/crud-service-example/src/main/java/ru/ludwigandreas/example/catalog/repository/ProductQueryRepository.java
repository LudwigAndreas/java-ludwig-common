package ru.ludwigandreas.example.catalog.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import ru.ludwigandreas.example.catalog.repository.entity.ProductEntity;
import ru.ludwigandreas.example.catalog.repository.query.ProductSearchCriteria;

/**
 * Custom repository fragment holding every product query this service runs.
 *
 * <p>The method names deliberately avoid Spring Data's derived-query vocabulary
 * ({@code findBySku}, {@code existsBySkuAndIdNot}, ...): those are parsed from the method name at
 * context startup and blow up at runtime on a typo or a renamed field. Everything here is
 * hand-written QueryDSL against generated Q-types instead, so a renamed or retyped entity field
 * breaks the compile.
 */
public interface ProductQueryRepository {

    /** Looks a product up by its natural key, case-insensitively. */
    Optional<ProductEntity> lookupBySku(String sku);

    /**
     * @param excludedId product allowed to already own the SKU (the one being updated), or
     *                   {@code null} when checking on create
     * @return whether another product already uses this SKU
     */
    boolean skuTaken(String sku, UUID excludedId);

    /** Runs an OData search, returning one page plus its total count. */
    Page<ProductEntity> search(ProductSearchCriteria criteria);
}
