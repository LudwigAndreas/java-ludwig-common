package ru.ludwigandreas.archrules.fixture.bad.operations.catalog.jobs;

/**
 * A richer lifecycle that maps onto the platform vocabulary rather than restating it, and is
 * therefore not a violation.
 *
 * <p>It is in the "bad" fixture on purpose: the rule has to be shown not firing on this one in the
 * same run in which it fires on {@link ExportJobStatus}, because a rule that catches the second
 * vocabulary by also catching every domain lifecycle is a rule that gets switched off.
 * {@code AWAITING_HANDLE} and {@code COLLECTING} carry information the common core cannot - the
 * first is a submit whose outcome is unknown, the second is a result being streamed back - and both
 * belong in {@code OperationResponse.detail()} beside the common-core state.
 */
public enum PartnerJobState {

    /** The call was made and we do not yet know whether the partner accepted it. */
    AWAITING_HANDLE,

    /** The partner reports it as in progress. */
    RUNNING,

    /** The partner reports it as finished; the result has not been collected yet. */
    SUCCEEDED,

    /** The result is being collected, resumable from a cursor. */
    COLLECTING,

    /** Everything was collected. */
    COLLECTED,

    /** The partner reports it as failed. */
    FAILED,

    /** It outlived its window. */
    EXPIRED
}
