package ru.ludwigandreas.odatafilter.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import ru.ludwigandreas.audit.AuditCategories;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.odatafilter.audit.FilterAppliedEvent;
import ru.ludwigandreas.odatafilter.metrics.NoopODataFilterMetrics;
import ru.ludwigandreas.odatafilter.policy.FilterPolicyRegistry;
import ru.ludwigandreas.odatafilter.properties.ODataFilterProperties;
import ru.ludwigandreas.odatafilter.querydsl.PredicateBuilder;
import ru.ludwigandreas.odatafilter.security.AnonymousFilterPrincipalResolver;
import ru.ludwigandreas.odatafilter.testmodel.Employee;

/**
 * That a filter application reaches {@code audit-core}'s one sink, that it carries no caller value, and
 * that this module does not decide what a sink failure means.
 */
class ODataFilterServiceAuditTest {

    /** Collects what the sink was handed, which is the only way to assert on a trail. */
    private static final class RecordingSink implements AuditSink {
        private final List<AuditEvent> recorded = new ArrayList<>();

        @Override
        public void record(AuditEvent event) {
            recorded.add(event);
        }
    }

    /** A sink mid-outage. The point of the test is what this module does *not* do about it. */
    private static final class FailingSink implements AuditSink {
        @Override
        public void record(AuditEvent event) {
            throw new IllegalStateException("the audit table is unreachable");
        }
    }

    private final ODataFilterProperties properties = new ODataFilterProperties();
    private final List<Object> published = new ArrayList<>();
    private final ApplicationEventPublisher publisher = new ApplicationEventPublisher() {
        @Override
        public void publishEvent(ApplicationEvent event) {
            published.add(event);
        }

        @Override
        public void publishEvent(Object event) {
            published.add(event);
        }
    };

    private ODataFilterService service(AuditSink sink) {
        return new ODataFilterService(
                properties,
                new FilterPolicyRegistry(properties),
                new PredicateBuilder(),
                new AnonymousFilterPrincipalResolver(),
                List.of(),
                publisher,
                new NoopODataFilterMetrics(),
                sink);
    }

    @Test
    @DisplayName("a successful parse reaches the sink, in the query category")
    void recordsToTheSink() {
        RecordingSink sink = new RecordingSink();

        service(sink).parse(Employee.class, ODataQueryOptions.filterOnly("name eq 'Alice'"));

        assertThat(sink.recorded).hasSize(1);
        AuditEvent event = sink.recorded.get(0);
        assertThat(event.category()).isEqualTo(AuditCategories.QUERY);
        assertThat(event.action()).isEqualTo("query.filtered");
        assertThat(event.resource().type()).isEqualTo("Employee");
    }

    @Test
    @DisplayName("the recorded event names the property and the operator, and not the value")
    void recordsPathsAndOperatorsOnly() {
        RecordingSink sink = new RecordingSink();

        service(sink).parse(Employee.class, ODataQueryOptions.filterOnly("name eq 'a.sidorov@example.com'"));

        AuditEvent event = sink.recorded.get(0);
        assertThat(event.attributes()).containsEntry("properties", "name").containsEntry("operators", "name eq");
        // The whole envelope, so a value smuggled into any attribute fails this.
        assertThat(event.attributes().toString()).doesNotContain("sidorov").doesNotContain("example.com");
    }

    @Test
    @DisplayName("a parse with no filter still records, because the read happened")
    void recordsAnUnfilteredRead() {
        RecordingSink sink = new RecordingSink();

        service(sink).parse(Employee.class, ODataQueryOptions.none());

        assertThat(sink.recorded).hasSize(1);
        assertThat(sink.recorded.get(0).attributes()).containsEntry("properties", "");
    }

    @Test
    @DisplayName("the in-process event is still published, alongside the sink and not instead of it")
    void publishesTheApplicationEventToo() {
        RecordingSink sink = new RecordingSink();

        service(sink).parse(Employee.class, ODataQueryOptions.filterOnly("age gt 30"));

        assertThat(sink.recorded).hasSize(1);
        assertThat(published).hasSize(1).first().isInstanceOf(FilterAppliedEvent.class);
        FilterAppliedEvent event = (FilterAppliedEvent) published.get(0);
        assertThat(event.entityType()).isEqualTo(Employee.class);
        assertThat(event.summary().operators()).containsExactly("age gt");
    }

    @Test
    @DisplayName("a rejected filter records nothing - a rejection is a metric, not a trail entry")
    void doesNotRecordARejection() {
        RecordingSink sink = new RecordingSink();

        assertThatThrownBy(() -> service(sink)
                .parse(Employee.class, ODataQueryOptions.filterOnly("secretNotes eq 'x'")))
                .isInstanceOf(RuntimeException.class);

        assertThat(sink.recorded).isEmpty();
        assertThat(published).isEmpty();
    }

    @Test
    @DisplayName("a sink that throws is not caught here, so AuditFailurePolicy decides the outcome")
    void doesNotSwallowASinkFailure() {
        // The deployment that configured FAIL_OPERATION for this category chose to lose the read rather
        // than lose the record of it. A catch in this library would silently override that choice.
        assertThatThrownBy(() -> service(new FailingSink())
                .parse(Employee.class, ODataQueryOptions.filterOnly("name eq 'Alice'")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit table is unreachable");
    }

    @Test
    @DisplayName("the sink is called before the in-process listeners, so a listener cannot stop the trail")
    void recordsBeforePublishing() {
        RecordingSink sink = new RecordingSink();
        List<String> order = new ArrayList<>();
        AuditSink ordering = event -> {
            order.add("sink");
            sink.record(event);
        };
        ODataFilterService service = new ODataFilterService(
                properties,
                new FilterPolicyRegistry(properties),
                new PredicateBuilder(),
                new AnonymousFilterPrincipalResolver(),
                List.of(),
                event -> order.add("publisher"),
                new NoopODataFilterMetrics(),
                ordering);

        service.parse(Employee.class, ODataQueryOptions.filterOnly("name eq 'Alice'"));

        assertThat(order).containsExactly("sink", "publisher");
    }
}
