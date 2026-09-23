package ru.ludwigandreas.reconciliation.api;

import java.time.Instant;
import java.util.Optional;

/**
 * What the engine is asking a {@link Fetcher.Paged} implementation for on this call.
 *
 * @param pageSize   how many records to return, from the task's {@code fetch.page-size}
 * @param updatedSince for an incremental task, the persisted watermark - the newest change timestamp
 *                     this task has already seen. Null for a full sweep. A partner that supports a
 *                     delta parameter turns a four-hour full pull into a one-minute one, which is
 *                     usually the difference between a sweep that can run often and one that cannot
 * @param runId      the run this page belongs to, for correlating the outbound call with the rows it
 *                   produced
 */
public record PageRequest(int pageSize, Instant updatedSince, java.util.UUID runId) {

    /** The incremental watermark, if this is an incremental pull. */
    public Optional<Instant> updatedSinceOrEmpty() {
        return Optional.ofNullable(updatedSince);
    }
}
