package ru.ludwigandreas.odatafilter.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.odatafilter.core.ODataFilterService;
import ru.ludwigandreas.odatafilter.core.ODataQueryOptions;
import ru.ludwigandreas.odatafilter.testmodel.Employee;

/**
 * How the sink gets into {@link ODataFilterService}, and what happens in an application that has no sink
 * bean at all - which is every consumer that took this starter without
 * {@code audit-spring-boot-starter}.
 */
class ODataFilterAuditWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ODataFilterMetricsAutoConfiguration.class,
                    ODataFilterAutoConfiguration.class));

    @Test
    @DisplayName("with no sink bean the context starts and a filter still parses")
    void fallsBackToTheNoopSink() {
        // audit-core is a plain library, so an application that has not added the audit starter has no
        // sink bean to find. The fallback keeps the filter working; what it must not do is make the
        // service catch, which would take AuditFailurePolicy's decision away from the deployment.
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ODataFilterService.class);
            assertThat(context.getBean(ODataFilterService.class)
                    .parse(Employee.class, ODataQueryOptions.filterOnly("name eq 'Alice'")))
                    .isNotNull();
        });
    }

    @Test
    @DisplayName("an application's own sink is the one that gets the event")
    void usesTheApplicationsSink() {
        List<AuditEvent> recorded = new ArrayList<>();
        runner.withBean(AuditSink.class, () -> (AuditSink) recorded::add)
                .run(context -> {
                    context.getBean(ODataFilterService.class)
                            .parse(Employee.class, ODataQueryOptions.filterOnly("name eq 'Alice'"));

                    assertThat(recorded).hasSize(1);
                    assertThat(recorded.get(0).category()).isEqualTo("query");
                });
    }
}
