package ru.ludwigandreas.example.catalog.web.grpc;

import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import ru.ludwigandreas.catalog.grpc.v1.CatalogServiceGrpc;
import ru.ludwigandreas.catalog.grpc.v1.GetProductRequest;
import ru.ludwigandreas.catalog.grpc.v1.GetProductsRequest;
import ru.ludwigandreas.catalog.grpc.v1.GetProductsResponse;
import ru.ludwigandreas.example.catalog.service.ProductService;
import ru.ludwigandreas.example.catalog.service.exception.InvalidProductIdException;
import ru.ludwigandreas.example.catalog.service.model.Product;
import ru.ludwigandreas.example.catalog.service.model.ProductBatch;
import ru.ludwigandreas.example.catalog.web.mapper.ProductMessageMapper;
import ru.ludwigandreas.grpc.server.LudwigGrpcService;

/**
 * The catalog's gRPC surface: the same product lookups the REST controller serves.
 *
 * <p>An adapter and nothing more - it maps the request, calls {@link ProductService}, and maps the
 * answer. Authorization, transactions and the data-level guard are on the service, which is the only
 * place they work: gRPC calls this object directly, not through a Spring proxy, so an annotation here
 * would compile and never run ({@code RuleGroup.GRPC} fails the build on one). A missing product is the
 * service's own {@code ProductNotFoundException}, rendered {@code NOT_FOUND} by the problem mapping both
 * transports share.
 */
@LudwigGrpcService
@RequiredArgsConstructor
public class CatalogGrpcService extends CatalogServiceGrpc.CatalogServiceImplBase {

    private final ProductService products;
    private final ProductMessageMapper mapper;

    @Override
    public void getProduct(GetProductRequest request,
                           StreamObserver<ru.ludwigandreas.catalog.grpc.v1.Product> reply) {
        reply.onNext(mapper.toMessage(products.get(id(request.getId()))));
        reply.onCompleted();
    }

    @Override
    public void getProducts(GetProductsRequest request, StreamObserver<GetProductsResponse> reply) {
        List<Product> found = request.getIdsList().stream().map(CatalogGrpcService::id).map(products::get).toList();
        reply.onNext(mapper.toResponse(new ProductBatch(found)));
        reply.onCompleted();
    }

    private static UUID id(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notAUuid) {
            throw new InvalidProductIdException(value);
        }
    }
}
