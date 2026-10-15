package ru.ludwigandreas.example.catalog.web.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import ru.ludwigandreas.catalog.grpc.v1.GetProductsResponse;
import ru.ludwigandreas.example.catalog.service.model.Category;
import ru.ludwigandreas.example.catalog.service.model.Product;
import ru.ludwigandreas.example.catalog.service.model.ProductBatch;
import ru.ludwigandreas.grpc.core.mapping.ProtobufMapperConfig;

/**
 * Between the catalog's gRPC messages and its service model - an interface, like every other mapper
 * in this service, with nothing written by hand.
 *
 * <p>{@link ProtobufMapperConfig} and the protobuf SPI on the annotation-processor path do the work:
 * an absent description leaves the message field unset and an unset one maps back to {@code null},
 * the batch's list fills the repeated field, {@code ProductState.DRAFT} meets
 * {@code PRODUCT_STATE_DRAFT}, and instants become timestamps. The supplier cost is in the model and
 * not in the message, so it is never mapped: the target decides.
 *
 * <p>The message-to-model direction is what a caller of this contract writes. It is declared here so
 * the reference shows both directions working.
 */
@Mapper(config = ProtobufMapperConfig.class)
public interface ProductMessageMapper {

    /**
     * A product as the message the contract returns.
     *
     * @param product the product
     * @return the message
     */
    ru.ludwigandreas.catalog.grpc.v1.Product toMessage(Product product);

    /**
     * A category as its message.
     *
     * @param category the category
     * @return the message
     */
    ru.ludwigandreas.catalog.grpc.v1.Category toMessage(Category category);

    /**
     * A batch as the response, in the batch's order.
     *
     * @param batch the products
     * @return the response
     */
    GetProductsResponse toResponse(ProductBatch batch);

    /**
     * A product message as the model, as a caller of this contract maps it.
     *
     * @param message the message
     * @return the product; its supplier cost is {@code null}, because the message has none
     */
    @Mapping(target = "supplierCost", ignore = true)
    Product toModel(ru.ludwigandreas.catalog.grpc.v1.Product message);
}
