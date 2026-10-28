package ru.ludwigandreas.reconciliation.api;

import java.util.function.Function;

/**
 * One enumeration: mirror everything a partner lists under a local scope.
 *
 * <p>A {@link SyncTask} answers "refresh these keys I already hold". This answers the question that
 * comes before it: "list everything under this scope and make my copy match". A <em>scope</em> is a
 * local row the integration already has - a stored search, a source, a root page - and a
 * <em>member</em> is an item the partner lists under it, which may have no local row yet.
 *
 * <h2>Why this is not a fifth {@link Fetcher} shape</h2>
 *
 * <p>{@code Fetcher} means "external state for a set of known keys", and
 * {@link DemandProvider#byKey} returning empty means "deleted locally, discard". An enumeration
 * contradicts both: its whole purpose is the record with no local row. Folding it into the paged
 * shape would make one empty {@code Optional} mean two opposite things.
 *
 * <h2>What the engine does not know</h2>
 *
 * <p>Depth. A tree is one task per level - this task's reconciler writes the rows another task's
 * {@link ScopeSource} reads - or one task whose scope source reads the rows its own reconciler
 * writes. Nothing here models a parent, a cascade or an order between levels, and each scope is
 * fresh on its own clock.
 *
 * @param <S>  the local scope type
 * @param <SK> the key that addresses a scope
 * @param <M>  the item the partner returns
 * @param <MK> the stable external id of an item; without one a member cannot be recognised twice
 */
public interface EnumerationTask<S, SK, M, MK> {

    /**
     * The task's name.
     *
     * @return a key under {@code ludwig.reconciliation.enumerations}; must match this bean's
     *         {@link ReconciliationEnumeration} value, and must not be the name of a {@link SyncTask}
     */
    String name();

    /**
     * Which local rows are scopes, and how to reload one.
     *
     * @return the scope source
     */
    ScopeSource<S, SK> scopes();

    /**
     * How a scope key survives a round trip through the {@code scope_key} column and an administrative path.
     *
     * @return the codec
     */
    KeyCodec<SK> scopeKeyCodec();

    /**
     * How the partner is asked for one page of a scope's members.
     *
     * @return the feed
     */
    MemberFeed<S, M> feed();

    /**
     * The stable external id of an item.
     *
     * @return the function that extracts it
     */
    Function<M, MK> memberKey();

    /**
     * How a member key survives a round trip through the {@code member_key} column.
     *
     * @return the codec
     */
    KeyCodec<MK> memberKeyCodec();

    /**
     * How an item becomes a local row, and what a member leaving a scope means.
     *
     * @return the reconciler
     */
    MemberReconciler<S, M, MK> reconciler();

    /**
     * How recent an item claims to be.
     *
     * <p>Only the timestamp is used, and only to advance a scope's watermark for incremental walks.
     * A task whose feed does not support a changed-since bound may leave the default.
     *
     * @param item an item from a page
     * @return its stamp
     */
    default ExternalStamp stampOf(M item) {
        return ExternalStamp.none();
    }
}
