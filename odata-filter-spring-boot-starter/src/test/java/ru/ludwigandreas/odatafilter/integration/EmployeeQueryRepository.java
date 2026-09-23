package ru.ludwigandreas.odatafilter.integration;

import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.ComparablePath;
import com.querydsl.core.types.dsl.PathBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import ru.ludwigandreas.odatafilter.core.ODataFilterService;
import ru.ludwigandreas.odatafilter.core.ODataQuery;
import ru.ludwigandreas.odatafilter.testmodel.Employee;

/**
 * The layer that owns {@link Employee}, and therefore the only one that parses the caller's OData
 * options: {@link ODataFilterService#parse} resolves the property paths against this entity and
 * hands back a QueryDSL predicate that only a query root of this type can execute.
 *
 * <p>Deliberately uses {@link JPAQueryFactory} against a dynamically built {@link PathBuilder}
 * root rather than a {@code QuerydslPredicateExecutor} repository, so this module's own test needs
 * no annotation-processor-generated {@code QEmployee} class. The alias must match the one the
 * library builds its predicate against - Spring Data's convention, the uncapitalized simple class
 * name - or the two would address different roots and the query would silently cross-join.
 */
@Repository
class EmployeeQueryRepository {

    private static final PathBuilder<Employee> ROOT = new PathBuilder<>(Employee.class, "employee");

    private final EntityManager entityManager;
    private final ODataFilterService filterService;

    EmployeeQueryRepository(EntityManager entityManager, ODataFilterService filterService) {
        this.entityManager = entityManager;
        this.filterService = filterService;
    }

    List<Employee> search(EmployeeSearchCriteria criteria) {
        ODataQuery<Employee> query = filterService.parse(
                Employee.class, criteria.filter(), criteria.top(), criteria.skip(), criteria.orderBy());
        Pageable pageable = query.pageable();

        return new JPAQueryFactory(entityManager)
                .selectFrom(ROOT)
                .where(query.predicate())
                .orderBy(toOrderSpecifiers(pageable.getSort()))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
    }

    private OrderSpecifier<?>[] toOrderSpecifiers(Sort sort) {
        return sort.stream()
                .map(order -> orderSpecifier(order.getProperty(), order.isAscending()))
                .toArray(OrderSpecifier<?>[]::new);
    }

    /** {@code Sort} carries nested paths as {@code department.name}, one segment per association hop. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private OrderSpecifier<?> orderSpecifier(String property, boolean ascending) {
        String[] segments = property.split("\\.");
        PathBuilder<?> parent = ROOT;
        for (int i = 0; i < segments.length - 1; i++) {
            parent = parent.get(segments[i]);
        }
        ComparablePath path = parent.getComparable(segments[segments.length - 1], Comparable.class);
        return new OrderSpecifier(ascending ? Order.ASC : Order.DESC, path);
    }
}
