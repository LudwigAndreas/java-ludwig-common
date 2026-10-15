package ru.ludwigandreas.archrules.fixture.bad.grpc.catalog.service;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.archrules.fixture.bad.grpc.catalog.repository.ProductRepository;

/**
 * Where the authorization and the transaction belong. The same two annotations that are a violation
 * on a gRPC service are correct here, because this bean is called through its proxy.
 */
@Service
public class ProductLookup {

    private final ProductRepository products;

    public ProductLookup(ProductRepository products) {
        this.products = products;
    }

    @Transactional
    @PreAuthorize("hasAuthority('catalog:read')")
    public String nameOf(String id) {
        return products.nameOf(id);
    }
}
