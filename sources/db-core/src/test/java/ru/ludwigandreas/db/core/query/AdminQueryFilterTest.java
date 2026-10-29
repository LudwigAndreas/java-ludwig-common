package ru.ludwigandreas.db.core.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.Expressions;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.ludwigandreas.db.core.config.AdminQueryFilterAutoConfiguration;
import ru.ludwigandreas.odatafilter.ast.FilterSummary;
import ru.ludwigandreas.odatafilter.core.ODataFilterService;
import ru.ludwigandreas.odatafilter.core.ODataQuery;

class AdminQueryFilterTest {

    static class Row {
    }

    @Test
    @DisplayName("through the OData starter: the predicate and the paths and operators, never the values")
    void odataBacked() {
        ODataFilterService service = mock(ODataFilterService.class);
        Predicate where = Expressions.TRUE.isTrue();
        when(service.parse(eq(Row.class), any())).thenAnswer(call -> new ODataQuery<>(where, null,
                "status eq 'FAILED'", false, new FilterSummary(Set.of("status"), Set.of("status eq"))));

        AdminQueryFilter.ParsedFilter parsed = new ODataAdminQueryFilter(service).parse(Row.class,
                "status eq 'FAILED'");

        assertThat(parsed.where()).contains(where);
        assertThat(parsed.constrains("status")).isTrue();
        assertThat(parsed.constrains("createdAt")).isFalse();
        assertThat(parsed.auditFacts()).containsEntry("filterPaths", java.util.List.of("status"))
                .containsEntry("filterOperators", java.util.List.of("status eq"));
        assertThat(parsed.auditFacts().toString()).doesNotContain("FAILED");
        assertThat(new ODataAdminQueryFilter(service).parse(Row.class, " ")).isEqualTo(
                AdminQueryFilter.ParsedFilter.none());
    }

    @Test
    @DisplayName("without the OData starter: no filter is none, a filter is refused")
    void unavailable() {
        UnavailableAdminQueryFilter filter = new UnavailableAdminQueryFilter();

        assertThat(filter.parse(Row.class, null).where()).isEmpty();
        assertThat(filter.parse(Row.class, null).auditFacts()).isEmpty();
        assertThatThrownBy(() -> filter.parse(Row.class, "status eq 'FAILED'"))
                .isInstanceOf(AdminFilterUnavailableException.class)
                .hasMessageContaining(AdminFilterUnavailableException.CODE);
    }

    @Test
    @DisplayName("the OData-backed filter where the service exists, the refusing one where it or its class does not")
    void wiring() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AdminQueryFilterAutoConfiguration.class));

        runner.withBean(ODataFilterService.class, () -> mock(ODataFilterService.class))
                .run(context -> assertThat(context).getBean(AdminQueryFilter.class)
                        .isInstanceOf(ODataAdminQueryFilter.class));
        runner.run(context -> assertThat(context).getBean(AdminQueryFilter.class)
                .isInstanceOf(UnavailableAdminQueryFilter.class));
        runner.withClassLoader(new FilteredClassLoader(ODataFilterService.class))
                .run(context -> assertThat(context).getBean(AdminQueryFilter.class)
                        .isInstanceOf(UnavailableAdminQueryFilter.class));
    }
}
