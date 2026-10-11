package ru.ludwigandreas.reconciliation.api;

/**
 * Turns an item into a local row, and decides what a member leaving a scope means.
 *
 * <p>Both methods run inside a transaction the engine opened, so writes made through the same
 * transaction manager commit together with the engine's own bookkeeping for the page: the membership
 * row, the payload hash and the cursor. Either all of a page is recorded or none of it is.
 *
 * <h2>What an implementation owes</h2>
 *
 * <ul>
 *   <li><b>An upsert, not an insert.</b> {@link MemberContext#isNew()} describes the engine's
 *       membership row, not the integration's table. The same item can be a member of two scopes, and
 *       a member that left and returned may still have a row that was only flagged as removed. Look
 *       the row up by external id - including removed rows - and restore or update it.</li>
 *   <li><b>Throwing when it cannot apply.</b> A thrown exception fails that member, not the scope:
 *       the engine retries it with backoff and quarantines it at the task's attempt budget.</li>
 *   <li><b>Treating "left" as leaving a scope.</b> It is not "deleted upstream". An item listed under
 *       two scopes that leaves one still exists.</li>
 * </ul>
 *
 * @param <S>  the local scope type
 * @param <M>  the item the partner returns
 * @param <MK> the stable external id of an item
 */
public interface MemberReconciler<S, M, MK> {

    /**
     * Creates or updates the local row for an item.
     *
     * <p>Not called for an item whose payload is identical to the one last applied for this scope;
     * the engine recognises that from its own hash and only notes that the member was seen.
     *
     * @param scope   the scope the item was listed under
     * @param item    the item
     * @param context what the engine knows about this member
     * @return what was done
     */
    MemberResult apply(S scope, M item, MemberContext context);

    /**
     * Applies the fact that a member is no longer listed under a scope.
     *
     * <p>Called once per member, and only on trustworthy evidence: after a complete, full,
     * failure-free walk that did not list the member, and never after an incremental walk. Whether
     * that means a soft delete, a hard delete, an unlink or nothing is the integration's decision; the
     * engine removes nothing but its own membership row.
     *
     * <p>The default keeps the local row, because quietly removing data on the strength of an absence
     * is a much worse default than doing nothing and counting it.
     *
     * @param scope     the scope the member left
     * @param memberKey the member's external id
     * @param context   what the engine knows about this member
     * @return whether anything was done
     */
    default LeftResult left(S scope, MK memberKey, MemberContext context) {
        return LeftResult.KEPT;
    }
}
