package ru.ludwigandreas.odatafilter.integration;

import com.querydsl.core.types.dsl.PathBuilder;
import org.springframework.stereotype.Repository;
import ru.ludwigandreas.odatafilter.core.ODataQueryOptions;
import ru.ludwigandreas.odatafilter.execution.ODataPage;
import ru.ludwigandreas.odatafilter.execution.ODataQueryExecutor;
import ru.ludwigandreas.odatafilter.execution.ODataSearch;
import ru.ludwigandreas.odatafilter.testmodel.Employee;

/**
 * The layer that owns {@link Employee}, and therefore the only one that parses the caller's OData
 * options.
 *
 * <p>The whole of it is one call. Before {@link ODataQueryExecutor} existed this class held a
 * {@code static final PathBuilder ROOT}, a {@code toOrderSpecifiers(Sort)}, a reflective
 * {@code orderSpecifier(String, boolean)} with a {@code @SuppressWarnings({"rawtypes","unchecked"})}
 * and its own {@code JPAQueryFactory} - and so did every other repository that used this module,
 * identically.
 *
 * <p>The root is a {@link PathBuilder} rather than a generated {@code QEmployee} because this module's
 * own tests deliberately run without the annotation processor. {@code PathBuilder} extends
 * {@code EntityPathBase}, so the executor takes it exactly as it takes a generated Q-type, and the
 * alias check applies to both.
 */
@Repository
class EmployeeQueryRepository {

    private static final PathBuilder<Employee> ROOT = new PathBuilder<>(Employee.class, "employee");

    private final ODataQueryExecutor executor;

    EmployeeQueryRepository(ODataQueryExecutor executor) {
        this.executor = executor;
    }

    ODataPage<Employee> search(ODataQueryOptions options) {
        return executor.search(Employee.class, options, ODataSearch.of(ROOT));
    }
}
