package ru.ludwigandreas.reconciliation.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What the engine is asking a {@link DemandProvider} for on this run.
 *
 * @param taskName    the task being run, so one provider can serve several tasks if it wants to
 * @param tier        which half of demand this run wants; see {@link DemandTier}
 * @param watermark   for an incremental task, the newest change timestamp already seen, so the query
 *                    can ask for what changed since. Null on a full sweep and on the first run
 * @param maxRecords  the cap from {@code demand.max-records-per-run}. Providers must honour it: it is
 *                    what stops one run of a task that has fallen a week behind from loading a
 *                    million rows into memory and taking the pod down with it
 * @param runId       identifies this run; stamped onto every row it produces so one partner
 *                    interaction is traceable end to end
 */
public record DemandRequest(String taskName, DemandTier tier, Instant watermark, int maxRecords, UUID runId) {

    /** The incremental watermark, if this task is incremental and has one. */
    public Optional<Instant> watermarkOrEmpty() {
        return Optional.ofNullable(watermark);
    }
}
