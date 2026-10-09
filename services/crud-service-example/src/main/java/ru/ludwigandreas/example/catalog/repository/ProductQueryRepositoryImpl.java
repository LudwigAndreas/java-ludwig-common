package ru.ludwigandreas.example.catalog.repository;

import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import ru.ludwigandreas.db.core.util.Predicates;
import ru.ludwigandreas.example.catalog.repository.entity.ProductEntity;
import ru.ludwigandreas.example.catalog.repository.entity.QProductEntity;
import ru.ludwigandreas.odatafilter.core.ODataQueryOptions;
import ru.ludwigandreas.odatafilter.execution.ODataPage;
import ru.ludwigandreas.odatafilter.execution.ODataQueryExecutor;
import ru.ludwigandreas.odatafilter.execution.ODataSearch;
import ru.ludwigandreas.security.data.DataAccessGuard;
import ru.ludwigandreas.security.data.DataAction;

/**
 * QueryDSL-JPA implementation of {@link ProductQueryRepository}, picked up by Spring Data through
 * the {@code <FragmentInterface>Impl} naming convention.
 *
 * <p>Every query goes through {@link JPAQueryFactory} and the generated {@link QProductEntity}: no
 * JDBC, no JPQL/SQL string, no method-name-derived query. The one dynamic piece is the client's
 * OData {@code $filter}, and that is not free-form either - {@link ODataQueryExecutor} only produces
 * paths that the entity's own {@code @Filterable} annotations allow, rejecting anything else before a
 * query is built.
 */
@RequiredArgsConstructor
class ProductQueryRepositoryImpl implements ProductQueryRepository {

    /** The name this resource's policies and scope mapping are registered under. */
    private static final String RESOURCE_TYPE = "product";

    private static final QProductEntity PRODUCT = QProductEntity.productEntity;

    private final JPAQueryFactory queryFactory;
    private final ODataQueryExecutor odataExecutor;
    private final DataAccessGuard dataAccessGuard;

    /**
     * Deliberately unscoped. This is the uniqueness check behind the SKU constraint, not a read of
     * someone's data: scoping it would let a caller create a product whose SKU collides with one they
     * cannot see, and the insert would then fail on the database constraint with an error nobody can
     * explain. The result never leaves the service - see {@code ProductServiceImpl}.
     */
    @Override
    public Optional<ProductEntity> lookupBySku(String sku) {
        return Optional.ofNullable(queryFactory.selectFrom(PRODUCT)
                .where(PRODUCT.sku.equalsIgnoreCase(sku))
                .fetchFirst());
    }

    @Override
    public boolean skuTaken(String sku, UUID excludedId) {
        return queryFactory.selectOne()
                .from(PRODUCT)
                .where(Predicates.allOf(
                        PRODUCT.sku.equalsIgnoreCase(sku),
                        Predicates.whenNotNull(excludedId, PRODUCT.id::ne)))
                .fetchFirst() != null;
    }

    /**
     * The caller's data scope is ANDed into the query itself, next to the client's own {@code $filter}.
     *
     * <p>That placement is the whole point. Filtering the page after fetching it would return fewer than
     * {@code $top} rows and a total that counts rows the caller may not see, so both the page and the
     * pager would be wrong; and the database would still have read and shipped those rows. In the
     * {@code WHERE} clause, paging, counting and the index all stay correct, and a caller entitled to
     * nothing simply gets an empty page rather than a 403 that would confirm matching products exist.
     *
     * <p>The ordering, the offset, the limit and the conditional count are all
     * {@link ODataQueryExecutor}'s. This method used to carry them: a {@code PathBuilder} field, an
     * {@code orderSpecifiers(Sort)}, a reflective {@code orderSpecifier(String, boolean)} with an
     * unchecked-cast suppression, a {@code PageableExecutionUtils} call and a private {@code count} -
     * all of which {@code notification-service} also carried, identically.
     */
    @Override
    public ODataPage<ProductEntity> search(ODataQueryOptions options) {
        return odataExecutor.search(ProductEntity.class, options, ODataSearch.of(PRODUCT)
                .and(dataAccessGuard.predicate(RESOURCE_TYPE, DataAction.READ))
                // The category is mapped into every response, so fetch it with the page instead of
                // paying one extra SELECT per row. It is a fetch join and therefore belongs only on the
                // content query - QueryDSL refuses one on a count, and a join that multiplied rows
                // would change the total.
                .content(query -> query.leftJoin(PRODUCT.category).fetchJoin()));
    }
}
