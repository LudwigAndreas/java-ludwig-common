package ru.ludwigandreas.example.catalog.web;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import ru.ludwigandreas.example.catalog.service.ProductService;
import ru.ludwigandreas.example.catalog.service.model.Product;
import ru.ludwigandreas.example.catalog.service.model.ProductQuery;
import ru.ludwigandreas.example.catalog.web.dto.CreateProductRequest;
import ru.ludwigandreas.webcore.web.PageResponse;
import ru.ludwigandreas.example.catalog.web.dto.ProductResponse;
import ru.ludwigandreas.example.catalog.web.dto.UpdateProductRequest;
import ru.ludwigandreas.example.catalog.web.mapper.ProductDtoMapper;

/**
 * HTTP surface of the catalog. It validates the request, maps it into the service model, and maps
 * the result back - no business rule, no transaction and no query lives here.
 *
 * <p>Authorization comes in two layers and both are visible here only as the first one. The
 * {@code @PreAuthorize} annotations are the resource-level gate - may this caller use this endpoint at
 * all - and they are the whole of what the web layer decides. Which products come back, and whether this
 * caller may touch <em>this</em> product, is data-level and lives where the rows are: the scoped query in
 * {@code ProductQueryRepositoryImpl} and the guard in {@code ProductServiceImpl}. Putting row rules in a
 * controller annotation is the usual way they end up enforced on one endpoint and forgotten on the next.
 *
 * <p>The search endpoint takes the OData options as plain request parameters and hands them to the
 * service unparsed. The starter's {@code ODataQuery<T>} argument resolver could bind them straight
 * into a predicate here, but only by naming the JPA entity in the controller signature; passing the
 * raw options down keeps the entity where it belongs.
 */
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final ProductDtoMapper mapper;

    @PostMapping
    @PreAuthorize("hasAnyRole('CATALOG_EDITOR', 'CATALOG_ADMIN', 'CATALOG_PARTNER')")
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest request,
                                                  UriComponentsBuilder uriBuilder) {
        Product created = productService.create(mapper.toCommand(request));
        URI location = uriBuilder.path("/api/v1/products/{id}").build(created.id());
        return ResponseEntity.created(location).body(mapper.toResponse(created));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('CATALOG_READER', 'CATALOG_EDITOR', 'CATALOG_ADMIN', "
            + "'CATALOG_PARTNER', 'CATALOG_WATCHER')")
    public ProductResponse get(@PathVariable UUID id) {
        return mapper.toResponse(productService.get(id));
    }

    /**
     * OData-style search, e.g.
     * {@code GET /api/v1/products?$filter=price lt 100 and category/code eq 'TOOLS'&$orderby=price desc&$top=20}.
     * Which fields may appear in {@code $filter}/{@code $orderby} is decided by the entity's
     * {@code @Filterable} annotations, not by this signature.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('CATALOG_READER', 'CATALOG_EDITOR', 'CATALOG_ADMIN', "
            + "'CATALOG_PARTNER', 'CATALOG_WATCHER')")
    public PageResponse<ProductResponse> search(
            @RequestParam(name = "$filter", required = false) String filter,
            @RequestParam(name = "$orderby", required = false) String orderBy,
            @RequestParam(name = "$top", required = false) Integer top,
            @RequestParam(name = "$skip", required = false) Integer skip) {
        Page<Product> page = productService.search(new ProductQuery(filter, orderBy, top, skip));
        return PageResponse.of(page.map(mapper::toResponse));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('CATALOG_EDITOR', 'CATALOG_ADMIN', 'CATALOG_PARTNER')")
    public ProductResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateProductRequest request) {
        return mapper.toResponse(productService.update(id, mapper.toCommand(request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('CATALOG_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        productService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
