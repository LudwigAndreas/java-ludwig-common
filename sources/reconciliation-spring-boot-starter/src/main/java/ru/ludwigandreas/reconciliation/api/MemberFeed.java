package ru.ludwigandreas.reconciliation.api;

/**
 * The partner's listing of a scope's members, one page at a time.
 *
 * <p>The only partner-specific part of an enumeration. Everything a filter the partner understands
 * can express belongs in the request this builds; a filter it cannot express belongs in
 * {@link MemberReconciler#apply}, which may answer {@link MemberResult#skipped()}.
 *
 * @param <S> the local scope type
 * @param <M> the item the partner returns
 */
public interface MemberFeed<S, M> {

    /**
     * Fetches one page of a scope's members.
     *
     * @param scope   the scope being walked
     * @param cursor  the token from the previous page's {@link PageResult#nextCursor()}, or null to
     *                start at the beginning
     * @param request page size and, on an incremental walk, the changed-since bound
     * @return the page and the token for the next one
     */
    PageResult<M> fetchPage(S scope, String cursor, FeedRequest request);

    /**
     * Whether this feed honours {@link FeedRequest#changedSince()}.
     *
     * <p>Declared rather than assumed because the engine cannot tell from a response whether a bound
     * was applied or ignored, and treating a full listing as a delta is harmless while treating a
     * delta as a full listing reports every unchanged member as gone. Incremental walks are configured
     * only for a feed that answers true; the startup validator refuses the other combination.
     *
     * @return true if a changed-since bound narrows the listing
     */
    default boolean supportsChangedSince() {
        return false;
    }
}
