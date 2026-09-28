package ru.ludwigandreas.archrules.fixture.good.catalog.web;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.ludwigandreas.archrules.fixture.good.catalog.service.ProductService;
import ru.ludwigandreas.archrules.fixture.good.catalog.service.model.Product;
import ru.ludwigandreas.archrules.fixture.good.catalog.web.dto.ProductResponse;
import ru.ludwigandreas.archrules.fixture.good.catalog.web.mapper.ProductDtoMapper;

/** Entry point: versioned base path, constructor-injected, DTOs only, no persistence in sight. */
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductService service;
    private final ProductDtoMapper mapper;

    public ProductController(ProductService service, ProductDtoMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public ProductResponse create(String code, String name) {
        Product product = service.create(code, name);
        return mapper.toResponse(product);
    }
}
