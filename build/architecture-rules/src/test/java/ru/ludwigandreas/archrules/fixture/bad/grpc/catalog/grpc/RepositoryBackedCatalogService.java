package ru.ludwigandreas.archrules.fixture.bad.grpc.catalog.grpc;

import ru.ludwigandreas.archrules.fixture.bad.grpc.catalog.repository.ProductRepository;
import ru.ludwigandreas.grpc.server.LudwigGrpcService;

/** An adapter that took the application service's job, and its transaction boundary with it. */
@LudwigGrpcService
public class RepositoryBackedCatalogService extends CatalogImplBase {

    private final ProductRepository products;

    public RepositoryBackedCatalogService(ProductRepository products) {
        this.products = products;
    }

    @Override
    public String getProduct(String id) {
        return products.nameOf(id);
    }
}
