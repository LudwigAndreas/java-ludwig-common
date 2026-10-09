package ru.ludwigandreas.odatafilter.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.odatafilter.properties.ODataFilterProperties;
import ru.ludwigandreas.odatafilter.exception.PageSizeExceededException;
import ru.ludwigandreas.odatafilter.exception.UnfilterableFieldException;
import ru.ludwigandreas.odatafilter.metrics.ODataFilterMetrics;
import ru.ludwigandreas.odatafilter.policy.FilterPolicyRegistry;
import ru.ludwigandreas.odatafilter.querydsl.PredicateBuilder;
import ru.ludwigandreas.odatafilter.security.AnonymousFilterPrincipalResolver;
import ru.ludwigandreas.odatafilter.testmodel.Employee;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import ru.ludwigandreas.audit.NoopAuditSink;

class ODataFilterServiceMetricsTest {

    private static class RecordingMetrics implements ODataFilterMetrics {
        final List<String> applied = new ArrayList<>();
        final List<String> rejectedReasons = new ArrayList<>();
        final List<String> metadataServed = new ArrayList<>();
        int durationsRecorded = 0;

        @Override
        public void recordFilterApplied(String entityType) {
            applied.add(entityType);
        }

        @Override
        public void recordFilterRejected(String entityType, String reason) {
            rejectedReasons.add(reason);
        }

        @Override
        public void recordParseDuration(String entityType, Duration duration) {
            durationsRecorded++;
        }

        @Override
        public void recordMetadataServed(String entityType) {
            metadataServed.add(entityType);
        }
    }

    private ODataFilterService service(RecordingMetrics metrics) {
        ODataFilterProperties properties = new ODataFilterProperties();
        return new ODataFilterService(
                properties,
                new FilterPolicyRegistry(properties),
                new PredicateBuilder(),
                new AnonymousFilterPrincipalResolver(),
                List.of(),
                null,
                metrics,
                new NoopAuditSink());
    }

    @Test
    void recordsAppliedAndDurationOnSuccess() {
        RecordingMetrics metrics = new RecordingMetrics();

        service(metrics).parse(Employee.class, ODataQueryOptions.filterOnly("name eq 'Alice'"));

        assertThat(metrics.applied).containsExactly("Employee");
        assertThat(metrics.rejectedReasons).isEmpty();
        assertThat(metrics.durationsRecorded).isEqualTo(1);
    }

    @Test
    void recordsRejectedWithTheExceptionTypeWhenAFieldIsNotFilterable() {
        RecordingMetrics metrics = new RecordingMetrics();

        assertThatThrownBy(() -> service(metrics)
                .parse(Employee.class, ODataQueryOptions.filterOnly("secretNotes eq 'x'")))
                .isInstanceOf(UnfilterableFieldException.class);

        assertThat(metrics.applied).isEmpty();
        assertThat(metrics.rejectedReasons).containsExactly("UnfilterableFieldException");
        assertThat(metrics.durationsRecorded).isEqualTo(1);
    }

    @Test
    void recordsRejectedWhenPageSizeIsExceeded() {
        RecordingMetrics metrics = new RecordingMetrics();

        assertThatThrownBy(() -> service(metrics).parse(Employee.class, ODataQueryOptions.of(null, null, 1000, null)))
                .isInstanceOf(PageSizeExceededException.class);

        assertThat(metrics.rejectedReasons).containsExactly("PageSizeExceededException");
    }

    @Test
    @DisplayName("parsing a filter does not touch the metadata counter")
    void parsingDoesNotCountAsMetadataServed() {
        RecordingMetrics metrics = new RecordingMetrics();

        service(metrics).parse(Employee.class, ODataQueryOptions.filterOnly("name eq 'Alice'"));

        // The two signals exist to be told apart: a rise in rejections is either a client bug or someone
        // probing, and this counter is what distinguishes the clients that asked properly.
        assertThat(metrics.metadataServed).isEmpty();
        assertThat(metrics.applied).containsExactly("Employee");
    }
}
