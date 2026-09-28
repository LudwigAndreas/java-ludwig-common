package ru.ludwigandreas.archrules.fixture.good.catalog.web.mapper;

import org.mapstruct.Mapper;

import ru.ludwigandreas.archrules.fixture.good.catalog.service.model.Product;
import ru.ludwigandreas.archrules.fixture.good.catalog.web.dto.ProductResponse;

/**
 * A mapper in the web package: a MapStruct interface, so the conversion cannot silently drop a field
 * and cannot grow logic without someone noticing that a method body appeared.
 */
@Mapper
public interface ProductDtoMapper {

    ProductResponse toResponse(Product product);
}
