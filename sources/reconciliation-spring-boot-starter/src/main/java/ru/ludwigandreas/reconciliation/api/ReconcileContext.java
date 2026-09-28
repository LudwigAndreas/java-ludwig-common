package ru.ludwigandreas.reconciliation.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What the engine knows about the record being applied, handed to the {@link Reconciler} so a
 * domain-level ordering decision does not require a second query.
 *
 * @param taskName        the task this record belongs to
 * @param runId           the run that fetched it
 * @param attempt         1-based apply attempt; a value above 1 means an earlier apply failed or
 *                        deferred, which is often reason enough to take a different branch
 * @param stamp           how recent the external record claims to be
 * @param lastAppliedStamp the stamp of the newest external state already applied for this key, or
 *                        {@link ExternalStamp#none()} if nothing has been applied yet. The engine has
 *                        already rejected anything strictly older by timestamp; this is here for the
 *                        orderings only the domain knows
 * @param receivedAt      when the record was staged
 */
public record ReconcileContext(String taskName,
                               UUID runId,
                               int attempt,
                               ExternalStamp stamp,
                               ExternalStamp lastAppliedStamp,
                               Instant receivedAt) {

    /** Whether anything has been applied for this key before. */
    public boolean isFirstApply() {
        return lastAppliedStamp == null || lastAppliedStamp.isEmpty();
    }

    /** The run that fetched this record. */
    public Optional<UUID> run() {
        return Optional.ofNullable(runId);
    }
}
