package ru.ludwigandreas.reconciliation.api;

/**
 * What a {@link MemberReconciler} did with an item.
 *
 * <p>There is no deferred or rejected outcome, unlike {@link ReconcileResult}: an enumeration has no
 * staged record to hold on to, and the next walk lists the item again. An item that cannot be applied
 * yet is reported by throwing, which records the failure on the member and retries it with backoff.
 */
public sealed interface MemberResult {

    /** The local row was created or changed. */
    record Applied(String detail) implements MemberResult {
    }

    /** The item is a member and nothing needed writing. */
    record Unchanged() implements MemberResult {
    }

    /**
     * The item is not wanted. The engine keeps no membership row for it, and if it held one, tells the
     * reconciler the member left the scope.
     */
    record Skipped(String reason) implements MemberResult {
    }

    /**
     * The local row was created or changed.
     *
     * @param detail what changed, for the log
     * @return the result
     */
    static MemberResult applied(String detail) {
        return new Applied(detail);
    }

    /**
     * The item is a member and nothing needed writing.
     *
     * @return the result
     */
    static MemberResult unchanged() {
        return new Unchanged();
    }

    /**
     * The item is not wanted - a filter the partner could not apply.
     *
     * @param reason why, for the log
     * @return the result
     */
    static MemberResult skipped(String reason) {
        return new Skipped(reason);
    }
}
