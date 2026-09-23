package ru.ludwigandreas.reconciliation.api;

import java.util.List;

/**
 * One page of external records, plus how to ask for the next one.
 *
 * <h2>Why the cursor is a first-class part of the contract</h2>
 *
 * <p>It is what lets a paged pull - and an asynchronous job's result collection, which reuses the
 * same walker - checkpoint and resume. Without it, an instance that dies four hundred thousand rows
 * into a sweep starts again at page zero on the next tick, re-reads everything it already staged, and
 * in the worst case never finishes because the sweep takes longer than the interval between crashes.
 *
 * @param <O>        the external record type
 * @param items      the records in this page, possibly empty
 * @param nextCursor opaque token to pass back for the following page; meaningful only to the partner
 * @param hasMore    whether another page exists. Explicit rather than inferred from an empty page or
 *                   a null cursor, because partners disagree about both: some return an empty final
 *                   page with a cursor, some return a cursor that yields nothing forever
 */
public record PageResult<O>(List<O> items, String nextCursor, boolean hasMore) {

    /** Defensive copy, so a fetcher reusing its buffer cannot mutate a page the engine is walking. */
    public PageResult {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** A final page: these records and nothing after them. */
    public static <O> PageResult<O> last(List<O> items) {
        return new PageResult<>(items, null, false);
    }

    /** A page with more to follow, reachable with {@code nextCursor}. */
    public static <O> PageResult<O> of(List<O> items, String nextCursor) {
        return new PageResult<>(items, nextCursor, true);
    }

    /** An empty final page. */
    public static <O> PageResult<O> empty() {
        return new PageResult<>(List.of(), null, false);
    }
}
