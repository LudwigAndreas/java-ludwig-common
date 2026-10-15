package ru.ludwigandreas.archrules.fixture.bad.grpc.catalog.grpc;

/** A service implementation the starter will never register, or that something else is binding. */
public class UnstereotypedCatalogService extends CatalogImplBase {

    @Override
    public String getProduct(String id) {
        return id;
    }
}
