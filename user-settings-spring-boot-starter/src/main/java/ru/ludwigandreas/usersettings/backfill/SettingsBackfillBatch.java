package ru.ludwigandreas.usersettings.backfill;

import java.util.UUID;

/**
 * One committed batch, and where to resume from.
 *
 * <p>{@code lastId} is a keyset cursor rather than an offset. Paging a table this size with
 * {@code OFFSET} degrades quadratically - the database re-walks and discards every row it already
 * skipped - and, worse, silently loses rows when a concurrent write shifts the ordering underneath
 * the scan. Resuming from the last primary key read has neither problem.
 *
 * <p>The ids are random UUIDs, so the order they impose is arbitrary. That is fine and is all a
 * keyset needs: the ordering has to be <em>stable</em> and total, not meaningful. Ordering by
 * {@code changed_at} would be meaningful and wrong, because it is not unique.
 *
 * @param rowCount how many rows this batch published; fewer than the batch size means the end
 * @param lastId   the highest id published, or null when the batch was empty
 */
public record SettingsBackfillBatch(int rowCount, UUID lastId) {

    public static final SettingsBackfillBatch EMPTY = new SettingsBackfillBatch(0, null);
}
