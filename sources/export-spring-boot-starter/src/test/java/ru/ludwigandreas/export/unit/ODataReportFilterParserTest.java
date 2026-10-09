package ru.ludwigandreas.export.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.querydsl.core.types.Predicate;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.audit.NoopAuditSink;
import ru.ludwigandreas.export.api.ReportDefinition;
import ru.ludwigandreas.export.filter.ODataReportFilterParser;
import ru.ludwigandreas.export.filter.ReportNotFilterableException;
import ru.ludwigandreas.odatafilter.annotation.FilterPolicy;
import ru.ludwigandreas.odatafilter.annotation.Filterable;
import ru.ludwigandreas.odatafilter.core.ODataFilterService;
import ru.ludwigandreas.odatafilter.metrics.NoopODataFilterMetrics;
import ru.ludwigandreas.odatafilter.policy.FilterPolicyRegistry;
import ru.ludwigandreas.odatafilter.properties.ODataFilterProperties;
import ru.ludwigandreas.odatafilter.querydsl.PredicateBuilder;
import ru.ludwigandreas.odatafilter.security.AnonymousFilterPrincipalResolver;

/**
 * A report has no paging and no ordering of its own to pass through, so what this parser returns is
 * only ever "a predicate" or "nothing" - and until the filter service could say "nothing", it could
 * not return the second.
 */
class ODataReportFilterParserTest {

    @Entity
    @FilterPolicy
    static class OrderEntity {
        @Id
        private Long id;

        @Filterable
        private String number;
    }

    private final ODataFilterProperties properties = new ODataFilterProperties();
    private final ODataFilterService filters = new ODataFilterService(
            properties,
            new FilterPolicyRegistry(properties),
            new PredicateBuilder(),
            new AnonymousFilterPrincipalResolver(),
            List.of(),
            null,
            new NoopODataFilterMetrics(),
            new NoopAuditSink());
    private final ODataReportFilterParser parser = new ODataReportFilterParser(filters);

    private ReportDefinition<?, ?> filterableDefinition() {
        return TestReports.orders()
                .filterEntityType(OrderEntity.class)
                .filterableColumns(java.util.Set.of("number"))
                .build();
    }

    @Test
    @DisplayName("a request with no filter yields nothing, so nothing is ANDed into the report query")
    void noFilterYieldsNothing() {
        Optional<Predicate> predicate = parser.parse(filterableDefinition(), null);

        // This is the assertion the old code could not satisfy: the service returned Expressions.TRUE,
        // so Optional.ofNullable always produced a present value and every unfiltered report carried an
        // unconditional AND true that no consumer could tell apart from a real condition.
        assertThat(predicate).isEmpty();
    }

    @Test
    @DisplayName("a blank filter is no filter either")
    void blankFilterYieldsNothing() {
        assertThat(parser.parse(filterableDefinition(), "   ")).isEmpty();
    }

    @Test
    @DisplayName("a real filter is translated and handed back")
    void realFilterYieldsAPredicate() {
        Optional<Predicate> predicate = parser.parse(filterableDefinition(), "number eq 'A-1'");

        assertThat(predicate).isPresent();
        assertThat(predicate.orElseThrow().toString()).contains("number");
    }

    @Test
    @DisplayName("a filter for a report that declares no entity is refused, not silently dropped")
    void unfilterableReportIsRefused() {
        ReportDefinition<?, ?> noEntity = TestReports.orders().build();

        assertThatThrownBy(() -> parser.parse(noEntity, "number eq 'A-1'"))
                .isInstanceOf(ReportNotFilterableException.class);
    }
}
