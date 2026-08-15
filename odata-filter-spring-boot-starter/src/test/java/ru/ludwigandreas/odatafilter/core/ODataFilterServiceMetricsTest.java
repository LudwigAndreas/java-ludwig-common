package ru.ludwigandreas.odatafilter.core;

import org.junit.jupiter.api.Test;
import ru.ludwigandreas.odatafilter.config.ODataFilterProperties;
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

class ODataFilterServiceMetricsTest {

    private static class RecordingMetrics implements ODataFilterMetrics {
        final List<String> applied = new ArrayList<>();
        final List<String> rejectedReasons = new ArrayList<>();
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
                metrics);
    }

    @Test
    void recordsAppliedAndDurationOnSuccess() {
        RecordingMetrics metrics = new RecordingMetrics();

        service(metrics).parse(Employee.class, "name eq 'Alice'", null, null, null, null);

        assertThat(metrics.applied).containsExactly("Employee");
        assertThat(metrics.rejectedReasons).isEmpty();
        assertThat(metrics.durationsRecorded).isEqualTo(1);
    }

    @Test
    void recordsRejectedWithTheExceptionTypeWhenAFieldIsNotFilterable() {
        RecordingMetrics metrics = new RecordingMetrics();

        assertThatThrownBy(() -> service(metrics)
                .parse(Employee.class, "secretNotes eq 'x'", null, null, null, null))
                .isInstanceOf(UnfilterableFieldException.class);

        assertThat(metrics.applied).isEmpty();
        assertThat(metrics.rejectedReasons).containsExactly("UnfilterableFieldException");
        assertThat(metrics.durationsRecorded).isEqualTo(1);
    }

    @Test
    void recordsRejectedWhenPageSizeIsExceeded() {
        RecordingMetrics metrics = new RecordingMetrics();

        assertThatThrownBy(() -> service(metrics).parse(Employee.class, null, 1000, null, null, null))
                .isInstanceOf(PageSizeExceededException.class);

        assertThat(metrics.rejectedReasons).containsExactly("PageSizeExceededException");
    }
}
