package ru.ludwigandreas.observability.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.tracing.exporter.FinishedSpan;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.observability.tracing.PathExcludingSpanExportingPredicate;

/** Keeps probe spans out of the tracing backend without dropping anything else. */
class PathExcludingSpanExportingPredicateTest {

    private final PathExcludingSpanExportingPredicate predicate =
            new PathExcludingSpanExportingPredicate(List.of("/actuator/**"));

    @Test
    void dropsAnExcludedPathFromTheMicrometerUriTag() {
        assertThat(predicate.isExportable(spanTagged("uri", "/actuator/health/liveness"))).isFalse();
    }

    @Test
    void dropsAnExcludedPathFromTheOpenTelemetryRouteTag() {
        assertThat(predicate.isExportable(spanTagged("http.route", "/actuator/prometheus"))).isFalse();
    }

    @Test
    void reducesAFullUrlToItsPathBeforeMatching() {
        // http.url carries scheme and authority. Matching "/actuator/**" against the whole URL never
        // succeeds, so without this the exclusion would look configured and silently do nothing.
        assertThat(predicate.isExportable(spanTagged("http.url", "https://svc.internal:8080/actuator/health")))
                .isFalse();
    }

    @Test
    void ignoresAQueryStringWhenMatching() {
        assertThat(predicate.isExportable(spanTagged("uri", "/actuator/metrics?tag=uri:/x"))).isFalse();
    }

    @Test
    void exportsBusinessTraffic() {
        assertThat(predicate.isExportable(spanTagged("uri", "/orders/4711"))).isTrue();
    }

    @Test
    void exportsASpanThatCarriesNoRecognisablePath() {
        // Internal spans, scheduled jobs and Kafka consumers have no path. Guessing here would
        // discard spans for a reason nobody could diagnose from the other end, where the only symptom
        // is a trace missing something no one knows to look for.
        assertThat(predicate.isExportable(spanTagged("db.system", "postgresql"))).isTrue();
    }

    @Test
    void exportsEverythingWhenNothingIsExcluded() {
        PathExcludingSpanExportingPredicate noExclusions = new PathExcludingSpanExportingPredicate(List.of());

        assertThat(noExclusions.isExportable(spanTagged("uri", "/actuator/health"))).isTrue();
    }

    private FinishedSpan spanTagged(String key, String value) {
        FinishedSpan span = mock(FinishedSpan.class);
        when(span.getTags()).thenReturn(Map.of(key, value));
        return span;
    }
}
