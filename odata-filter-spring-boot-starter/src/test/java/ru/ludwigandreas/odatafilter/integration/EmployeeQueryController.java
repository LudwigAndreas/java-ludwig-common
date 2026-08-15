package ru.ludwigandreas.odatafilter.integration;

import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.ComparablePath;
import com.querydsl.core.types.dsl.PathBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.odatafilter.core.ODataQuery;
import ru.ludwigandreas.odatafilter.testmodel.Employee;

/**
 * Minimal controller used only by this module's own integration test, to exercise the full
 * request -> argument resolver -> ODataFilterService -> QueryDSL predicate -> Postgres chain.
 * Deliberately uses {@link JPAQueryFactory} directly against the library's dynamically-built
 * {@link PathBuilder} root, rather than a {@code QuerydslPredicateExecutor} repository, so the
 * test needs no annotation-processor-generated {@code QEmployee} class.
 */
@RestController
@RequestMapping("/employees")
class EmployeeQueryController {

    private final EntityManager entityManager;

    EmployeeQueryController(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @GetMapping
    List<EmployeeView> search(ODataQuery<Employee> query) {
        PathBuilder<Employee> root = new PathBuilder<>(Employee.class, "employee");
        JPAQueryFactory queryFactory = new JPAQueryFactory(entityManager);

        return queryFactory.selectFrom(root)
                .where(query.predicate())
                .offset(query.pageable().getOffset())
                .limit(query.pageable().getPageSize())
                .orderBy(toOrderSpecifiers(root, query.pageable().getSort()))
                .fetch()
                .stream()
                .map(EmployeeView::of)
                .toList();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private OrderSpecifier<?>[] toOrderSpecifiers(PathBuilder<Employee> root, Sort sort) {
        return sort.stream()
                .map(order -> {
                    ComparablePath path = root.getComparable(order.getProperty(), Comparable.class);
                    return new OrderSpecifier(order.isAscending() ? Order.ASC : Order.DESC, path);
                })
                .toArray(OrderSpecifier[]::new);
    }

    record EmployeeView(String name, Integer age, BigDecimal salary, String status, String department) {
        static EmployeeView of(Employee employee) {
            return new EmployeeView(
                    employee.getName(),
                    employee.getAge(),
                    employee.getSalary(),
                    employee.getStatus(),
                    employee.getDepartment() == null ? null : employee.getDepartment().getName());
        }
    }
}
