package ru.ludwigandreas.odatafilter.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import ru.ludwigandreas.odatafilter.annotation.FilterPolicy;
import ru.ludwigandreas.odatafilter.annotation.Filterable;
import ru.ludwigandreas.odatafilter.config.ODataFilterProperties;
import ru.ludwigandreas.odatafilter.metrics.NoopODataFilterMetrics;
import ru.ludwigandreas.odatafilter.policy.FilterPolicyRegistry;
import ru.ludwigandreas.odatafilter.querydsl.PredicateBuilder;
import ru.ludwigandreas.odatafilter.security.AnonymousFilterPrincipalResolver;
import ru.ludwigandreas.odatafilter.testmodel.Employee;

/**
 * The entity's {@code defaultOrderBy} is what keeps {@code $skip}-based paging deterministic, so
 * these assertions are about a row appearing on exactly one page - not about cosmetics.
 */
class ODataFilterServiceOrderingTest {

    private final ODataFilterProperties properties = new ODataFilterProperties();
    private final ODataFilterService service = new ODataFilterService(
            properties,
            new FilterPolicyRegistry(properties),
            new PredicateBuilder(),
            new AnonymousFilterPrincipalResolver(),
            List.of(),
            null,
            new NoopODataFilterMetrics());

    @Test
    void appliesTheEntityDefaultWhenTheCallerAsksForNoOrdering() {
        Sort sort = service.parse(Employee.class, null, null, null, null).pageable().getSort();

        assertThat(sort).containsExactly(Sort.Order.asc("id"));
    }

    @Test
    void appendsTheEntityDefaultAfterTheCallersOwnOrdering() {
        Sort sort = service.parse(Employee.class, null, null, null, "name desc").pageable().getSort();

        // Without the appended tie-breaker, two employees sharing a name have no defined order
        // between them, and a row can then repeat across pages or be skipped entirely.
        assertThat(sort).containsExactly(Sort.Order.desc("name"), Sort.Order.asc("id"));
    }

    @Test
    void doesNotLetTheDefaultOverrideADirectionTheCallerChose() {
        Sort sort = service.parse(Ticket.class, null, null, null, "name desc").pageable().getSort();

        assertThat(sort).containsExactly(Sort.Order.desc("name"));
    }

    @Test
    void ordersByNothingWhenNeitherTheCallerNorTheEntityAsksForAnOrdering() {
        assertThat(service.parse(Unordered.class, null, null, null, null).pageable().getSort().isSorted())
                .isFalse();
    }

    @Test
    void translatesNestedOrderPathsToJpaNotation() {
        Sort sort = service.parse(Employee.class, null, null, null, "department/name asc").pageable().getSort();

        assertThat(sort).containsExactly(Sort.Order.asc("department.name"), Sort.Order.asc("id"));
    }

    /** Its default ordering names a field clients may sort on, which the Employee tie-breaker does not. */
    @FilterPolicy(defaultOrderBy = "name asc")
    static class Ticket {
        @Filterable
        String name;
    }

    static class Unordered {
        @Filterable
        String name;
    }
}
