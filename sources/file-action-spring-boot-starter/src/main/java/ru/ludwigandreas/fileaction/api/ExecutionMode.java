package ru.ludwigandreas.fileaction.api;

/**
 * Whether the caller waits for the work.
 *
 * <h2>This is a property, not a second API</h2>
 *
 * <p>Both modes answer with the same {@code OperationResponse}, built by {@code OperationResponses}. Inline is
 * simply the case where the envelope is already terminal when it is returned - which the platform's
 * long-running-operation contract explicitly permits, in the requirement "A 202 may carry a terminal envelope,
 * and a 200 must".
 *
 * <p>What that buys is worth stating: a deployment that discovers its inline action is too slow changes one
 * property, and no client changes at all. Had the two been separate endpoints, the same discovery would have
 * been a breaking API change, which is the kind of thing that does not get made and so is lived with.
 */
public enum ExecutionMode {

    /**
     * The submit request reads, validates and - in {@code DIRECT} mode - applies, then answers.
     *
     * <p>Bounded by {@code ludwig.file-action.inline.max-rows}: an inline action configured for a hundred
     * thousand rows is a request timeout and a held connection, and the application refuses to start rather
     * than discovering that in production.
     */
    INLINE,

    /**
     * The submit request stores, claims and answers; a worker does the rest.
     *
     * <p>Claimed under a lease, so a pod dying mid-apply releases the submission to another instance.
     */
    DEFERRED
}
