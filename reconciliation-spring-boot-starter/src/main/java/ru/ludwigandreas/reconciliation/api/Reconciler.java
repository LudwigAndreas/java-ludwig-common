package ru.ludwigandreas.reconciliation.api;

/**
 * Applies external state to a local record.
 *
 * <p>Runs inside its own transaction, one per record, holding a freshly loaded {@code I}. The engine
 * has already done the work that is the same for every integration - staging, the payload-hash
 * short-circuit, and the generic stale-stamp check against the newest external state already applied
 * for this key - so what is left here is the part that is genuinely domain-specific: mapping the
 * partner's representation onto this service's, and deciding whether the result is acceptable.
 *
 * <h2>What an implementation still owes</h2>
 *
 * <ul>
 *   <li><b>Its own staleness check where the generic one cannot help.</b> The engine can compare
 *       {@link ExternalStamp}s; it cannot know that a partner's {@code CANCELLED} must never
 *       overwrite a local {@code REFUNDED}. Where the domain has its own ordering, enforce it and
 *       return {@link ReconcileResult#rejected(String)}.</li>
 *   <li><b>Writing through the entity, not around it.</b> Local writes go through JPA so that
 *       {@code db-core}'s optimistic locking sees them. A native update that bypasses the version
 *       column removes the last line of defence against two instances applying different external
 *       state to the same record in the same instant.</li>
 *   <li><b>Returning {@link ReconcileResult#unchanged()} when nothing changed.</b> The engine's hash
 *       short-circuit catches an identical payload; a payload that differs in a field this service
 *       does not map still reaches here, and reporting it as applied churns {@code updated_at} on
 *       every poll.</li>
 * </ul>
 *
 * @param <I> the local record type
 * @param <O> the external record type
 */
public interface Reconciler<I, O> {

    /**
     * Applies {@code external} to {@code local}.
     *
     * @param local    the freshly loaded local record
     * @param external the external state
     * @param context  what the engine knows about this record's history, for domain-level ordering
     *                 decisions the generic stamp comparison cannot make
     * @return what was done
     */
    ReconcileResult reconcile(I local, O external, ReconcileContext context);

    /**
     * Applies the fact that the partner no longer knows this record.
     *
     * <p>Called only under {@code not-found: mark-missing}, which is how a task says that "gone
     * upstream" is meaningful to its domain - a deactivated account, a withdrawn catalogue entry.
     *
     * <p>The default rejects, because for most integrations a key the partner has forgotten is a fact
     * about their data rather than an instruction about ours, and quietly deleting or deactivating a
     * local record on the strength of a 404 is a much worse default than doing nothing and saying so.
     *
     * @param local   the freshly loaded local record
     * @param context what the engine knows about this record's history
     * @return what was done
     */
    default ReconcileResult reconcileMissing(I local, ReconcileContext context) {
        return ReconcileResult.rejected("The partner no longer knows this key, and this task's "
                + "reconciler does not define what that should mean locally");
    }
}
