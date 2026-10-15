package ru.ludwigandreas.archrules.fixture.bad.grpc.catalog.grpc;

import io.grpc.BindableService;

/**
 * Stands in for a protoc-generated {@code XxxImplBase}: abstract, and itself a
 * {@code BindableService}. The stereotype rule must not ask this class for the stereotype.
 */
public abstract class CatalogImplBase implements BindableService {

    @Override
    public Object bindService() {
        return this;
    }

    public String getProduct(String id) {
        throw new UnsupportedOperationException(id);
    }
}
