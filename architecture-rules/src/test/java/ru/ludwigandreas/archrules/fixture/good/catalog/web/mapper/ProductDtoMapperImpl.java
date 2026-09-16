package ru.ludwigandreas.archrules.fixture.good.catalog.web.mapper;

import ru.ludwigandreas.archrules.fixture.good.catalog.service.model.Product;
import ru.ludwigandreas.archrules.fixture.good.catalog.web.dto.ProductResponse;

/**
 * Stands in for the class MapStruct's annotation processor generates: a class, in a mapper package,
 * whose name ends in the mapper suffix - exactly the case the mapper rule has to exclude. It does so
 * by assignability, because MapStruct's own {@code @Generated} marker has source retention and never
 * reaches the bytecode.
 */
public class ProductDtoMapperImpl implements ProductDtoMapper {

    /**
     * MapStruct emits anonymous classes next to the mapper implementation (its enum lookup tables,
     * for instance). They sit in the mapper package and are neither interfaces nor annotated, so the
     * rule has to ignore nested types - this field is here to keep that true.
     */
    private static final java.util.Comparator<String> ORDER = new java.util.Comparator<>() {

        @Override
        public int compare(String first, String second) {
            return first.compareTo(second);
        }
    };

    @Override
    public ProductResponse toResponse(Product product) {
        ORDER.compare(product.name(), product.name());
        return new ProductResponse(product.code().value(), product.name());
    }
}
