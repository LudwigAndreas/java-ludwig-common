package ru.ludwigandreas.example.catalog.service;

import java.util.UUID;
import ru.ludwigandreas.example.catalog.service.model.NewProduct;
import ru.ludwigandreas.example.catalog.service.model.Product;
import ru.ludwigandreas.odatafilter.core.ODataQueryOptions;
import ru.ludwigandreas.odatafilter.execution.ODataPage;
import ru.ludwigandreas.example.catalog.service.model.ProductUpdate;

/**
 * Product CRUD as the business layer sees it: domain models in, domain models out, no HTTP and no
 * JPA in any signature.
 */
public interface ProductService {

    Product create(NewProduct command);

    Product get(UUID id);

    ODataPage<Product> search(ODataQueryOptions options);

    Product update(UUID id, ProductUpdate command);

    void delete(UUID id);
}
