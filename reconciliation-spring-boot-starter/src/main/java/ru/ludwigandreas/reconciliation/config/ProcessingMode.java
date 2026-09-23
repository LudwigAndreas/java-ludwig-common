package ru.ludwigandreas.reconciliation.config;

/**
 * Whether fetched records are staged before being applied.
 */
public enum ProcessingMode {

    /**
     * Fetch, write down, apply later. The default, and what everything else in this module is built
     * around: per-item retry without re-calling the partner, a replayable record of what the partner
     * said, per-record dead-lettering instead of per-run failure, and an apply cadence independent of
     * the fetch cadence.
     */
    STAGED,

    /**
     * Apply inline, with no staging row.
     *
     * <p>Cheaper - one table's worth of writes less - and strictly worse in every failure mode: a
     * retry re-hits the partner, one unmappable record fails the run, and nothing is left behind to
     * inspect afterwards. Available on purpose for integrations where the data is cheap to refetch
     * and not worth a row, and an explicit opt-in so that nobody ends up here by accident.
     */
    DIRECT
}
