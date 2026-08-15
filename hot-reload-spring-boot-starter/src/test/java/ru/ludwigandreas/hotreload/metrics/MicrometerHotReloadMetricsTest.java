package ru.ludwigandreas.hotreload.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerHotReloadMetricsTest {

    @Test
    void recordsSucceededCounterAndChangedKeyCountSummary() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerHotReloadMetrics metrics = new MicrometerHotReloadMetrics(registry);

        metrics.recordReloadSucceeded("file:/etc/app.properties", 3);
        metrics.recordReloadSucceeded("file:/etc/app.properties", 1);

        assertThat(registry.get("ludwig.hotreload.reload.succeeded")
                .tag("source", "file:/etc/app.properties")
                .counter().count()).isEqualTo(2.0);
        assertThat(registry.get("ludwig.hotreload.reload.changed.keys")
                .tag("source", "file:/etc/app.properties")
                .summary().count()).isEqualTo(2);
    }

    @Test
    void recordsFailedCounterTaggedWithTheExceptionType() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerHotReloadMetrics metrics = new MicrometerHotReloadMetrics(registry);

        metrics.recordReloadFailed("vault:secret/myapp", "IllegalStateException");

        assertThat(registry.get("ludwig.hotreload.reload.failed")
                .tag("source", "vault:secret/myapp")
                .tag("exception", "IllegalStateException")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void registersASecondsSinceLastSuccessGaugeOnFirstSuccessAndUpdatesItOnEachSubsequentOne() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerHotReloadMetrics metrics = new MicrometerHotReloadMetrics(registry);

        metrics.recordReloadSucceeded("file:/etc/app.properties", 1);

        double secondsSinceSuccess = registry.get("ludwig.hotreload.reload.seconds.since.last.success")
                .tag("source", "file:/etc/app.properties")
                .gauge()
                .value();

        // freshly recorded, should read as (very close to) zero
        assertThat(secondsSinceSuccess).isBetween(0.0, 1.0);
    }
}
