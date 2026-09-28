package ru.ludwigandreas.example.catalog.service;

import java.util.UUID;
import org.springframework.data.domain.Page;
import ru.ludwigandreas.example.catalog.service.model.NewProduct;
import ru.ludwigandreas.example.catalog.service.model.Product;
import ru.ludwigandreas.example.catalog.service.model.ProductQuery;
import ru.ludwigandreas.example.catalog.service.model.ProductUpdate;

/**
 * Product CRUD as the business layer sees it: domain models in, domain models out, no HTTP and no
 * JPA in any signature.
 */
public interface ProductService {

    Product create(NewProduct command);

    Product get(UUID id);

    Page<Product> search(ProductQuery query);

    Product update(UUID id, ProductUpdate command);

    void delete(UUID id);
}
