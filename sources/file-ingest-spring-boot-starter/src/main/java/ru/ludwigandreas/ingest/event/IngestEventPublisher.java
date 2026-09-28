package ru.ludwigandreas.ingest.event;

import ru.ludwigandreas.ingest.api.IngestRunSummary;

/**
 * Where "the ingest finished" goes.
 *
 * <p>An interface with a no-op default, so that the engine has no branch for whether events are
 * enabled and no null to check - the same arrangement the metrics use, for the same reason.
 */
public interface IngestEventPublisher {

    /**
     * Announces a completed run.
     *
     * @param summary what the run did
     */
    void completed(IngestRunSummary summary);
}
