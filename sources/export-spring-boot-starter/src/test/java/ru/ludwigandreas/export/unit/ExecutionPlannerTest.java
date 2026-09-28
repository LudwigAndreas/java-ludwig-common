package ru.ludwigandreas.export.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.Expressions;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.export.api.Column;
import ru.ludwigandreas.export.api.NoParameters;
import ru.ludwigandreas.export.api.ReportDefinition;
import ru.ludwigandreas.export.api.ReportDefinitionSource;
import ru.ludwigandreas.export.api.ReportFormat;
import ru.ludwigandreas.export.api.ReportRequest;
import ru.ludwigandreas.export.api.SortKey;
import ru.ludwigandreas.export.api.StandardReportFormats;
import ru.ludwigandreas.export.config.ExportProperties;
import ru.ludwigandreas.export.engine.DefaultExecutionPlanner;
import ru.ludwigandreas.export.engine.ExecutionPlan;
import ru.ludwigandreas.export.engine.ExecutionPlanner;
import ru.ludwigandreas.export.engine.ReportFilterParser;
import ru.ludwigandreas.export.engine.ReportParameterBinder;
import ru.ludwigandreas.export.engine.ReportScopeResolver;
import ru.ludwigandreas.export.exception.FormatNotAllowedException;
import ru.ludwigandreas.export.exception.InvalidColumnSelectionException;
import ru.ludwigandreas.export.exception.ReportForbiddenException;
import ru.ludwigandreas.export.exception.UnknownFormatOptionException;
import ru.ludwigandreas.export.registry.MessageKeyValidator;
import ru.ludwigandreas.export.registry.ReportDefinitionRegistry;
import ru.ludwigandreas.export.unit.EngineFixtures.Sale;

/**
 * What a requester is and is not allowed to get.
 *
 * <p>Every case here is a refusal, because the planner's job is almost entirely refusing: it is the
 * one place a report's scope, columns and formats are decided, and the engine below it has no code
 * that performs a check and therefore none that can forget one.
 */
class ExecutionPlannerTest {

    private static final MessageKeyValidator ALL_KEYS = (key, locale) -> true;
    private static final Predicate SCOPE = Expressions.asBoolean(true).isTrue();

    private static final ReportParameterBinder BINDER = new ReportParameterBinder() {
        @Override
        @SuppressWarnings("unchecked")
        public <P extends ru.ludwigandreas.export.api.ReportParameters> P bind(
                Class<P> type, Map<String, String> values) {
            return (P) NoParameters.INSTANCE;
        }
    };

    private static Column<Sale, String> restrictedColumn() {
        return Column.<Sale, String>of("region", String.class)
                .extractor(Sale::region)
                .headerKey("test.column.region")
                .visibleFor(Set.of("ROLE_FINANCE"))
                .build();
    }

    private ExecutionPlanner planner(ReportDefinition<?, ?> definition, ReportScopeResolver scopes,
                                     ReportFilterParser filters, ExportProperties properties) {
        ReportDefinitionSource source = () -> List.of(definition);
        ReportDefinitionRegistry registry = new ReportDefinitionRegistry(List.of(source),
                Map.of("csv", StandardReportFormats.CSV, "xlsx", StandardReportFormats.XLSX),
                ALL_KEYS);
        return new DefaultExecutionPlanner(registry,
                List.of(EngineFixtures.csvFactory(), EngineFixtures.xlsxStubFactory()), BINDER,
                scopes, filters, EngineFixtures.MESSAGES, properties);
    }

    private ExecutionPlanner planner(ReportDefinition<?, ?> definition) {
        return planner(definition, ReportScopeResolver.UNRESTRICTED,
                (definitionKey, filter) -> Optional.empty(), new ExportProperties());
    }

    private static ReportRequest request(List<String> columnIds, List<ReportFormat> formats,
                                         Map<String, Map<String, String>> options, String filter) {
        return new ReportRequest("catalog.sales", Map.of(), columnIds, filter, List.<SortKey>of(),
                formats, options, Locale.ENGLISH, ZoneId.of("UTC"), null, "test-key");
    }

    @Test
    @DisplayName("a plan carries the visible columns, the resolved sheets and one output per format")
    void plansAWellFormedRequest() {
        var definition = EngineFixtures.definition(List.of(), List.of());
        ExecutionPlan<?, ?> plan = planner(definition).plan(UUID.randomUUID(),
                request(List.of(), List.of(StandardReportFormats.CSV), Map.of(), null),
                "alice", Set.of(), true);

        assertThat(plan.getColumns()).hasSize(4);
        assertThat(plan.getSheetSpecs()).hasSize(1);
        assertThat(plan.getSheetSpecs().get(0).title()).isEqualTo("Sales");
        assertThat(plan.getOutputs()).hasSize(1);
        assertThat(plan.getOutputs().get(0).format()).isEqualTo(StandardReportFormats.CSV);
        assertThat(plan.getPredicate()).isEmpty();
    }

    @Test
    @DisplayName("a requester without the report's required authority is refused before anything else")
    void refusesAReportTheyMayNotRun() {
        var definition = EngineFixtures.definitionBuilder(List.of(), List.of())
                .requiredAuthorities(Set.of("ROLE_REPORTS"))
                .build();

        assertThatThrownBy(() -> planner(definition).plan(UUID.randomUUID(),
                request(List.of(), List.of(StandardReportFormats.CSV), Map.of(), null),
                "alice", Set.of("ROLE_SALES"), true))
                .isInstanceOf(ReportForbiddenException.class)
                .hasMessageContaining("catalog.sales");
    }

    @Test
    @DisplayName("asking for no subset silently gets only the columns the requester may see")
    void narrowsSilentlyWhenNoSubsetIsAskedFor() {
        var definition = EngineFixtures.definitionBuilder(List.of(), List.of())
                .column(restrictedColumn())
                .build();

        ExecutionPlan<?, ?> plan = planner(definition).plan(UUID.randomUUID(),
                request(List.of(), List.of(StandardReportFormats.CSV), Map.of(), null),
                "alice", Set.of(), true);

        assertThat(plan.getColumns()).extracting(Column::getId)
                .containsExactly("reference", "day", "amount", "settled");
    }

    @Test
    @DisplayName("asking for a column they may not see is a 403 naming it, not a silent narrowing")
    void refusesAnExplicitlyRequestedForbiddenColumn() {
        var definition = EngineFixtures.definitionBuilder(List.of(), List.of())
                .column(restrictedColumn())
                .build();

        assertThatThrownBy(() -> planner(definition).plan(UUID.randomUUID(),
                request(List.of("reference", "region"), List.of(StandardReportFormats.CSV),
                        Map.of(), null), "alice", Set.of(), true))
                .isInstanceOf(InvalidColumnSelectionException.class)
                .hasMessageContaining("region");
    }

    @Test
    @DisplayName("a column the definition does not declare is a 400 naming what it does")
    void refusesAnUnknownColumn() {
        assertThatThrownBy(() -> planner(EngineFixtures.definition(List.of(), List.of()))
                .plan(UUID.randomUUID(), request(List.of("nothing"),
                        List.of(StandardReportFormats.CSV), Map.of(), null), "alice", Set.of(), true))
                .isInstanceOf(InvalidColumnSelectionException.class)
                .hasMessageContaining("nothing");
    }

    @Test
    @DisplayName("a format the definition does not allow is refused, naming the ones it does")
    void refusesADisallowedFormat() {
        assertThatThrownBy(() -> planner(EngineFixtures.definition(List.of(), List.of()))
                .plan(UUID.randomUUID(), request(List.of(), List.of(StandardReportFormats.XLSX),
                        Map.of(), null), "alice", Set.of(), true))
                .isInstanceOf(FormatNotAllowedException.class)
                .hasMessageContaining("xlsx");
    }

    @Test
    @DisplayName("an option the writer does not accept is rejected, not ignored")
    void refusesAnUnknownOption() {
        assertThatThrownBy(() -> planner(EngineFixtures.definition(List.of(), List.of()))
                .plan(UUID.randomUUID(), request(List.of(), List.of(StandardReportFormats.CSV),
                        Map.of("csv", Map.of("colour", "blue")), null), "alice", Set.of(), true))
                .isInstanceOf(UnknownFormatOptionException.class)
                .hasMessageContaining("colour");
    }

    @Test
    @DisplayName("the scope predicate is applied whether or not a filter was supplied")
    void alwaysAppliesTheScope() {
        ReportScopeResolver scoped = (definition, principalId, authorities) -> Optional.of(SCOPE);

        ExecutionPlan<?, ?> withoutFilter = planner(EngineFixtures.definition(List.of(), List.of()),
                scoped, (definition, filter) -> Optional.empty(), new ExportProperties())
                .plan(UUID.randomUUID(), request(List.of(), List.of(StandardReportFormats.CSV),
                        Map.of(), null), "alice", Set.of(), true);

        assertThat(withoutFilter.getPredicate()).isPresent();
    }

    @Test
    @DisplayName("a filter and a scope are conjoined, never substituted for one another")
    void conjoinsFilterAndScope() {
        ReportScopeResolver scoped = (definition, principalId, authorities) -> Optional.of(SCOPE);
        ReportFilterParser parser = (definition, filter) ->
                Optional.of(Expressions.asBoolean(false).isTrue());

        ExecutionPlan<?, ?> plan = planner(EngineFixtures.definition(List.of(), List.of()), scoped,
                parser, new ExportProperties())
                .plan(UUID.randomUUID(), request(List.of(), List.of(StandardReportFormats.CSV),
                        Map.of(), "region eq 'north'"), "alice", Set.of(), true);

        // Both are in the predicate: a filter narrows what was asked for, a scope narrows what they
        // are entitled to, and a report that applied only the filter would have the access control
        // removed by the act of asking a question.
        assertThat(plan.getPredicate()).isPresent();
        assertThat(plan.getPredicate().get().toString()).contains("&&");
    }

    @Test
    @DisplayName("the row cap is the lower of the definition's and the estate's")
    void takesTheLowerRowCap() {
        ExportProperties properties = new ExportProperties();
        properties.setMaxRowsPerRun(1_000);
        var definition = EngineFixtures.definitionBuilder(List.of(), List.of())
                .maxRows(5_000_000L)
                .build();

        ExecutionPlan<?, ?> plan = planner(definition, ReportScopeResolver.UNRESTRICTED,
                (d, f) -> Optional.empty(), properties)
                .plan(UUID.randomUUID(), request(List.of(), List.of(StandardReportFormats.CSV),
                        Map.of(), null), "alice", Set.of(), true);

        assertThat(plan.getRowCap()).isEqualTo(1_000);
    }
}
