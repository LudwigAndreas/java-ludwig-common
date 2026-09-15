package ru.ludwigandreas.example.catalog.service.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import ru.ludwigandreas.example.catalog.repository.entity.CategoryEntity;
import ru.ludwigandreas.example.catalog.repository.entity.ProductEntity;
import ru.ludwigandreas.example.catalog.service.event.ProductEventPayload;
import ru.ludwigandreas.example.catalog.service.model.Category;
import ru.ludwigandreas.example.catalog.service.model.NewProduct;
import ru.ludwigandreas.example.catalog.service.model.Product;
import ru.ludwigandreas.example.catalog.service.model.ProductUpdate;

/**
 * Boundary between the service model and the persistence model, generated at compile time by
 * MapStruct - no hand-written field-by-field copying, and a compile error the moment the two models
 * drift apart (the build sets {@code unmappedTargetPolicy=ERROR}).
 *
 * <p>Note what is <em>not</em> mapped on update: the id, the SKU and every audit/version column.
 * Those belong to db-core's base entity and to Hibernate, and the mapper is explicitly forbidden
 * from touching them.
 */
@Mapper
public interface ProductEntityMapper {

    @Mapping(target = "state", source = "status")
    Product toDomain(ProductEntity entity);

    Category toDomain(CategoryEntity entity);

    // "name" exists on both sources, so it has to be qualified - MapStruct refuses to guess.
    // supplierPartnerId is a scope column: it is stamped from the authenticated caller in
    // ProductServiceImpl, never taken from a request body, so the mapper must not fill it.
    @Mapping(target = "supplierPartnerId", ignore = true)
    // Watchers are managed through their own endpoint, never through a create or update payload: a
    // client able to add itself as a watcher could grant itself read access to any product.
    @Mapping(target = "watcherSubjects", ignore = true)
    @Mapping(target = "name", source = "command.name")
    @Mapping(target = "status", source = "command.state")
    @Mapping(target = "category", source = "category")
    ProductEntity toEntity(NewProduct command, CategoryEntity category);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "sku", ignore = true)
    // Immutable after creation: a partner must not be able to move a product to another partner.
    @Mapping(target = "supplierPartnerId", ignore = true)
    @Mapping(target = "watcherSubjects", ignore = true)
    @Mapping(target = "name", source = "command.name")
    @Mapping(target = "status", source = "command.state")
    @Mapping(target = "category", source = "category")
    void apply(ProductUpdate command, CategoryEntity category, @MappingTarget ProductEntity entity);

    @Mapping(target = "status", source = "state")
    @Mapping(target = "categoryCode", source = "category.code")
    ProductEventPayload toPayload(Product product);
}
