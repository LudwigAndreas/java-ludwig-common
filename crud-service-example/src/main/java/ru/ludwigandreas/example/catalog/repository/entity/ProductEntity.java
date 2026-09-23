package ru.ludwigandreas.example.catalog.repository.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.ludwigandreas.db.core.entity.AuditedEntity;
import ru.ludwigandreas.odatafilter.annotation.FilterOperator;
import ru.ludwigandreas.odatafilter.annotation.FilterPolicy;
import ru.ludwigandreas.odatafilter.annotation.Filterable;

/**
 * The persistence model - the third and innermost of this service's three models (see
 * {@code web.dto} and {@code service.model} for the other two). It never leaves the repository
 * layer: {@code ProductEntityMapper} converts it to the service-layer {@code Product} before the
 * service returns anything.
 *
 * <p>{@link Filterable} declares which columns clients may filter and sort on through OData; every
 * field left un-annotated ({@code description}) is unreachable from a query string no matter what a
 * client sends, and {@code supplierCost} is reachable only for callers holding
 * {@code ROLE_CATALOG_ADMIN}.
 */
@Entity
@Table(name = "product")
@FilterPolicy(maxDepth = 4, maxPageSize = 100, defaultPageSize = 20, maxNestedPropertyDepth = 2,
        defaultOrderBy = "createdAt desc, id asc")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductEntity extends AuditedEntity<UUID> {

    /** Natural key. Immutable once assigned - a product's identity to the outside world. */
    @Filterable(ops = {FilterOperator.EQ, FilterOperator.NE, FilterOperator.IN,
            FilterOperator.STARTSWITH})
    @Column(name = "sku", nullable = false, updatable = false, length = 64)
    private String sku;

    @Filterable
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** Free text: not filterable, so no client can turn it into a full-table {@code LIKE} scan. */
    @Column(name = "description", length = 2000)
    private String description;

    @Filterable(ops = {FilterOperator.EQ, FilterOperator.NE, FilterOperator.GT, FilterOperator.GE,
            FilterOperator.LT, FilterOperator.LE, FilterOperator.IN})
    @Column(name = "price", nullable = false, precision = 19, scale = 2)
    private BigDecimal price;

    /** What the product costs us - filterable by catalog admins only, never by ordinary callers. */
    @Filterable(roles = "ROLE_CATALOG_ADMIN",
            ops = {FilterOperator.EQ, FilterOperator.NE, FilterOperator.GT, FilterOperator.GE,
                    FilterOperator.LT, FilterOperator.LE})
    @Column(name = "supplier_cost", precision = 19, scale = 2)
    private BigDecimal supplierCost;

    @Enumerated(EnumType.STRING)
    @Filterable(ops = {FilterOperator.EQ, FilterOperator.NE, FilterOperator.IN}, sortable = false)
    @Column(name = "status", nullable = false, length = 32)
    private ProductStatus status;

    @Filterable(ops = {FilterOperator.EQ, FilterOperator.NE, FilterOperator.GT, FilterOperator.GE,
            FilterOperator.LT, FilterOperator.LE})
    @Column(name = "stock_quantity", nullable = false)
    private int stockQuantity;

    /**
     * Annotated so that {@code $filter=category/code eq 'TOOLS'} resolves: traversal into an
     * association is opt-in, and this is where the catalog decides that a product's category is
     * part of its published filter surface.
     */
    @Filterable
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private CategoryEntity category;

    /**
     * The partner this product belongs to, or {@code null} for catalog-owned products.
     *
     * <p>Deliberately not {@link Filterable}: it is a scope column, and a client able to filter on it
     * could enumerate which partner ids exist by watching which values return rows. Scoping reads it
     * through the {@code DataScopeMapping} in {@code SecurityConfig}, which never goes through OData.
     */
    @Column(name = "supplier_partner_id", length = 128)
    private String supplierPartnerId;

    /**
     * Subjects explicitly put on this product - the people who asked to follow it, or were assigned to
     * it. The membership answer to "show me everything I am named on", as opposed to the ownership
     * answer {@code createdBy} gives.
     *
     * <p>Eager because every scoped load needs it: the data-scope post-check reads this collection to
     * decide whether the caller may see the row at all, and a lazy one would throw the moment that check
     * ran outside a transaction.
     */
    @ElementCollection(fetch = jakarta.persistence.FetchType.EAGER)
    @CollectionTable(name = "product_watcher",
            joinColumns = @jakarta.persistence.JoinColumn(name = "product_id", nullable = false))
    @Column(name = "watcher_subject", nullable = false, length = 255)
    private Set<String> watcherSubjects = new LinkedHashSet<>();
}
