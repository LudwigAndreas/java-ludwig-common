package ru.ludwigandreas.reconciliation.engine;

import ru.ludwigandreas.reconciliation.api.FetchOutcome;

import java.util.List;

/**
 * Where a walker hands its outcomes.
 *
 * <p>A callback rather than a returned list, so that a sweep of a large catalogue stages each page as
 * it arrives instead of accumulating four hundred thousand records in memory first and writing them
 * all at the end - which is both the largest heap spike a service of this shape can have and the
 * worst possible time to discover the pod's memory limit.
 *
 * @param <K> correlation key type
 * @param <O> external record type
 */
@FunctionalInterface
public interface FetchSink<K, O> {

    /**
     * Accepts a batch of outcomes.
     *
     * @param outcomes the batch
     * @return how many rows it produced
     */
    int accept(List<FetchOutcome<K, O>> outcomes);
}
