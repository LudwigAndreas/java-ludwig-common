package ru.ludwigandreas.reconciliation.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What the engine is asking a {@link MemberFeed} for on this call.
 *
 * @param pageSize     how many items to return, from the task's {@code walk.page-size}
 * @param changedSince on an incremental walk, the scope's watermark - the newest change timestamp a
 *                     completed walk of this scope has seen. Null on a full walk, and always null for
 *                     a feed that does not declare {@link MemberFeed#supportsChangedSince()}
 * @param runId        the run this page belongs to, for correlating the outbound call
 */
public record FeedRequest(int pageSize, Instant changedSince, UUID runId) {

    /** The changed-since bound, if this is an incremental walk. */
    public Optional<Instant> changedSinceOrEmpty() {
        return Optional.ofNullable(changedSince);
    }

    /** Whether this call belongs to a full walk, the only kind that can show a member is gone. */
    public boolean isFullWalk() {
        return changedSince == null;
    }
}
