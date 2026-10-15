package ru.ludwigandreas.archrules.fixture.bad.grpc.catalog.grpc;

import ru.ludwigandreas.archrules.fixture.bad.grpc.catalog.service.ProductLookup;
import ru.ludwigandreas.grpc.server.LudwigGrpcService;

/**
 * The one correct service in this package: stereotyped, unannotated, delegating. It is here, among
 * the violations, so that each rule is shown to stay silent on the right shape in the same run in
 * which it fires on the wrong one.
 */
@LudwigGrpcService
public class DelegatingCatalogService extends CatalogImplBase {

    private final ProductLookup lookup;

    public DelegatingCatalogService(ProductLookup lookup) {
        this.lookup = lookup;
    }

    @Override
    public String getProduct(String id) {
        return lookup.nameOf(id);
    }
}
