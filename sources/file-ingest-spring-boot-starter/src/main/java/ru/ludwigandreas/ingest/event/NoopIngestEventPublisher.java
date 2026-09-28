package ru.ludwigandreas.ingest.event;

import ru.ludwigandreas.ingest.api.IngestRunSummary;

/**
 * Announces nothing.
 *
 * <p>The default. Publishing an event nobody consumes fills a table, so a service turns events on
 * deliberately; and a real bean rather than a null keeps the one code path in the engine.
 */
public class NoopIngestEventPublisher implements IngestEventPublisher {

    @Override
    public void completed(IngestRunSummary summary) {
        // Intentionally empty: see this class's documentation.
    }
}
