package ru.ludwigandreas.identity.directory.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;

/** {@code ludwig_identity_directory_run_seconds{source,kind,outcome}}. Sources are declared, so tags are bounded. */
public final class MicrometerDirectoryMetrics implements DirectoryMetrics {

    private final MeterRegistry registry;

    public MicrometerDirectoryMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void run(String source, String kind, String outcome, Duration elapsed) {
        Timer.builder("ludwig.identity.directory.run")
                .description("Directory syncs and sweeps of an identity source, by outcome")
                .tag("source", source).tag("kind", kind).tag("outcome", outcome)
                .register(registry).record(elapsed);
    }
}
