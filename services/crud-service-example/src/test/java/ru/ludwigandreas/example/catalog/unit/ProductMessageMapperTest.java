package ru.ludwigandreas.example.catalog.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import ru.ludwigandreas.catalog.grpc.v1.GetProductsResponse;
import ru.ludwigandreas.example.catalog.service.model.Category;
import ru.ludwigandreas.example.catalog.service.model.Product;
import ru.ludwigandreas.example.catalog.service.model.ProductState;
import ru.ludwigandreas.example.catalog.service.model.ProductBatch;
import ru.ludwigandreas.example.catalog.web.mapper.ProductMessageMapper;

/**
 * The generated mapper between the catalog's messages and its model: presence, repeated fields,
 * enum prefixes, decimals and instants - with no conversion code written by hand.
 */
class ProductMessageMapperTest {

    private static final Instant CREATED = Instant.parse("2026-10-15T09:00:00.123Z");
    private static final Category TOOLS = new Category(UUID.randomUUID(), "TOOLS", "Tools");

    private final ProductMessageMapper mapper = Mappers.getMapper(ProductMessageMapper.class);

    private static Product product(String sku, String description) {
        return new Product(UUID.randomUUID(), sku, "Hammer", description, new BigDecimal("19.99"),
                new BigDecimal("7.50"), ProductState.ACTIVE, 10, TOOLS, CREATED, CREATED.plusSeconds(60), 3);
    }

    @Test
    @DisplayName("an absent optional value leaves the message field unset, without an exception")
    void absentOptionalToMessage() {
        ru.ludwigandreas.catalog.grpc.v1.Product message = mapper.toMessage(product("H-1", null));

        assertThat(message.hasDescription()).isFalse();
    }

    @Test
    @DisplayName("an unset optional field maps back to null, not to the empty string")
    void unsetOptionalToModel() {
        ru.ludwigandreas.catalog.grpc.v1.Product message = mapper.toMessage(product("H-2", null));

        assertThat(mapper.toModel(message).description()).isNull();
        assertThat(mapper.toModel(mapper.toMessage(product("H-3", "Steel"))).description()).isEqualTo("Steel");
    }

    @Test
    @DisplayName("three products fill the repeated field in order, and map back in the same order")
    void repeatedField() {
        List<Product> three = List.of(product("A", null), product("B", "b"), product("C", null));

        GetProductsResponse response = mapper.toResponse(new ProductBatch(three));

        assertThat(response.getProductsList()).extracting(ru.ludwigandreas.catalog.grpc.v1.Product::getSku)
                .containsExactly("A", "B", "C");
        assertThat(response.getProductsList().stream().map(mapper::toModel).map(Product::id).toList())
                .isEqualTo(three.stream().map(Product::id).toList());
    }

    @Test
    @DisplayName("every value the REST response carries survives the round trip; the supplier cost is not sent")
    void roundTrip() {
        Product original = product("H-4", "Steel");

        ru.ludwigandreas.catalog.grpc.v1.Product message = mapper.toMessage(original);
        Product back = mapper.toModel(message);

        assertThat(message.getState()).isEqualTo(ru.ludwigandreas.catalog.grpc.v1.ProductState.PRODUCT_STATE_ACTIVE);
        assertThat(message.getPrice()).isEqualTo("19.99");
        assertThat(back).usingRecursiveComparison().ignoringFields("supplierCost").isEqualTo(original);
        assertThat(back.supplierCost()).isNull();
    }
}
