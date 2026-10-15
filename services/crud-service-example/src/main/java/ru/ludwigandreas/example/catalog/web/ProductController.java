package ru.ludwigandreas.example.catalog.web;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import ru.ludwigandreas.example.catalog.service.ProductService;
import ru.ludwigandreas.example.catalog.service.model.Product;
import ru.ludwigandreas.example.catalog.web.dto.CreateProductRequest;
import ru.ludwigandreas.odatafilter.core.ODataQueryOptions;
import ru.ludwigandreas.odatafilter.execution.ODataPage;
import ru.ludwigandreas.webcore.web.PageResponse;
import ru.ludwigandreas.example.catalog.web.dto.ProductResponse;
import ru.ludwigandreas.example.catalog.web.dto.UpdateProductRequest;
import ru.ludwigandreas.example.catalog.web.mapper.ProductDtoMapper;

/**
 * HTTP surface of the catalog. It validates the request, maps it into the service model, and maps
 * the result back - no business rule, no transaction and no query lives here.
 *
 * <p>Authorization comes in two layers, and neither lives here any more. The resource-level gate - may
 * this caller read, change or delete products at all - is the {@code @PreAuthorize} on
 * {@code ProductServiceImpl}, because this controller is no longer the only way in: the same products
 * are served over gRPC by {@code CatalogGrpcService}, where an annotation on the adapter would never be
 * applied. The data-level gate - which products, and whether <em>this</em> one - lives where the rows
 * are: the scoped query in {@code ProductQueryRepositoryImpl} and the guard in {@code ProductServiceImpl}.
 * The single exception is {@code create}, whose reason is at its annotation.
 *
 * <p>The search endpoint takes the OData options as plain request parameters and hands them to the
 * service unparsed. The starter's deprecated {@code ODataQuery<T>} argument resolver would bind
 * them straight into a predicate here, but only by naming the JPA entity in the controller
 * signature; passing the raw options down keeps the entity - and the query - where they belong.
 */
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final ProductDtoMapper mapper;

    @PostMapping
    // The one role check still on the controller. Every other method of this API is checked on
    // ProductServiceImpl, so REST and gRPC pass the same check. create is not: its other way in is
    // the product-import file action, which checks the submitter's authority at submission and
    // applies rows later on a worker with no caller, where a role check would reject every row.
    @PreAuthorize("hasAnyRole('CATALOG_EDITOR', 'CATALOG_ADMIN', 'CATALOG_PARTNER')")
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest request,
                                                  UriComponentsBuilder uriBuilder) {
        Product created = productService.create(mapper.toCommand(request));
        URI location = uriBuilder.path("/api/v1/products/{id}").build(created.id());
        return ResponseEntity.created(location).body(mapper.toResponse(created));
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable UUID id) {
        return mapper.toResponse(productService.get(id));
    }

    /**
     * OData-style search, e.g.
     * {@code GET /api/v1/products?$filter=price lt 100 and category/code eq 'TOOLS'&$orderby=price desc&$top=20}.
     * Which fields may appear in {@code $filter}/{@code $orderby} is decided by the entity's
     * {@code @Filterable} annotations, not by this signature.
     *
     * <p>The five options arrive as one {@link ODataQueryOptions}, bound by the starter's own argument
     * resolver and described in the OpenAPI document by its customizer. They travel down unparsed, so
     * neither this signature nor the service's names a JPA entity or a QueryDSL type.
     */
    @GetMapping
    public PageResponse<ProductResponse> search(ODataQueryOptions options) {
        ODataPage<ProductResponse> page = productService.search(options).map(mapper::toResponse);
        return PageResponse.of(page.content(), page.offset(), page.size(), page.totalElements());
    }

    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateProductRequest request) {
        return mapper.toResponse(productService.update(id, mapper.toCommand(request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        productService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
