package ru.ludwigandreas.reconciliation.api;

import java.util.List;
import java.util.Optional;

/**
 * Answers "which local records need external state right now?", and "give me that one local record
 * again".
 *
 * <h2>Why there are two methods</h2>
 *
 * <p>The second is what staging costs. A staged record is applied later - possibly minutes later,
 * possibly by a different instance, possibly after a restart - and applying it to the {@code I} the
 * demand query returned would be applying it to a snapshot taken before the fetch. By then the local
 * record may have been changed by a user, by another integration, or by an earlier staged record from
 * the same run. Reloading it inside the apply transaction is the only way the optimistic locking in
 * {@code db-core} can do its job.
 *
 * @param <I> the local record type
 * @param <K> the correlation key type
 */
public interface DemandProvider<I, K> {

    /**
     * The local records that need external state on this run.
     *
     * @param request tier, watermark and the cap this run must respect
     * @return the records, at most {@code request.maxRecords()} of them
     */
    List<I> demand(DemandRequest request);

    /**
     * Reloads one local record, fresh, by correlation key.
     *
     * @param key the correlation key
     * @return the record, or empty if it has since been deleted locally - which is not an error:
     *         staged external state for a record that no longer exists is simply discarded
     */
    Optional<I> byKey(K key);
}
