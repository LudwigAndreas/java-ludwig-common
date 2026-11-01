package ru.ludwigandreas.identity.unit;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.identity.feed.ApplyOutcome;
import ru.ludwigandreas.identity.projection.MicrometerIdentityMetrics;

class IdentityMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerIdentityMetrics metrics = new MicrometerIdentityMetrics(registry);

    @Test
    @DisplayName("a change is counted by source, type and outcome")
    void countsChanges() {
        metrics.change("staff", "upsert", ApplyOutcome.STALE);
        metrics.change("staff", "upsert", ApplyOutcome.STALE);

        assertThat(registry.get("ludwig.identity.changes").tag("source", "staff").tag("type", "upsert")
                .tag("outcome", "stale").counter().count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("a sweep is counted by outcome, and the users it disabled are summed per source")
    void countsSweeps() {
        metrics.sweep("staff", false, 3);
        metrics.sweep("staff", true, 0);

        assertThat(registry.get("ludwig.identity.sweeps").tag("outcome", "completed").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("ludwig.identity.sweeps").tag("outcome", "aborted").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("ludwig.identity.sweep.disabled").tag("source", "staff").counter().count())
                .isEqualTo(3.0);
    }
}
