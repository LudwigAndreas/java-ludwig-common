package ru.ludwigandreas.reconciliation.engine;

import ru.ludwigandreas.reconciliation.api.DemandTier;

import java.time.Instant;
import java.util.UUID;

/**
 * Identifies one run, and travels with it.
 *
 * <p>The run id is stamped onto every staged row and every audit event the run produces, and the
 * correlation id is propagated into the outbound calls it makes. Together they are what turns "the
 * partner says we called them 4,000 times at 03:14" into a single query.
 *
 * @param taskName      the task
 * @param tier          which half of demand this run is for
 * @param runId         this run
 * @param correlationId the correlation id of the unit of work, from the observability starter
 * @param startedAt     when the run began
 * @param deadline      when the run must stop, from {@code schedule.run-timeout}
 */
public record RunContext(String taskName,
                         DemandTier tier,
                         UUID runId,
                         String correlationId,
                         Instant startedAt,
                         Instant deadline) {

    /** Whether the run has used up its {@code run-timeout}. */
    public boolean isExpired() {
        return Instant.now().isAfter(deadline);
    }

    /** The tier as a metric tag. */
    public String tierTag() {
        return tier.name().toLowerCase(java.util.Locale.ROOT);
    }
}
