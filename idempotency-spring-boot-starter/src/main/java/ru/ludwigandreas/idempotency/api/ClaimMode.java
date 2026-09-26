package ru.ludwigandreas.idempotency.api;

/**
 * How a claim's own lifetime relates to the lifetime of the work it protects.
 *
 * <p>There is no default, and choosing is a required decision at every call site. The two modes are
 * not two implementations of one idea with different performance: they answer a duplicate
 * differently, they fail differently, and picking the wrong one is a correctness bug that only
 * appears under concurrency. A default here would be a decision somebody inherits without reading
 * this page.
 *
 * <table>
 *   <caption>The two modes</caption>
 *   <tr><th></th><th>{@link #TRANSACTIONAL}</th><th>{@link #STANDALONE}</th></tr>
 *   <tr><td>propagation</td><td>{@code MANDATORY}</td><td>{@code REQUIRES_NEW}</td></tr>
 *   <tr><td>a duplicate is told</td><td>"done, here is the owner"</td>
 *       <td>"done, here is the response" or "in flight, come back"</td></tr>
 *   <tr><td>a rollback</td><td>frees the key</td><td>leaves a {@code FAILED} claim, reclaimable</td></tr>
 *   <tr><td>a dead holder</td><td>cannot happen - the key was never committed</td>
 *       <td>its lease expires and the key becomes claimable</td></tr>
 * </table>
 */
public enum ClaimMode {

    /**
     * The claim commits with the caller's work, in the caller's transaction.
     *
     * <p>The mode the consumer pattern needs, and the one the primitive was originally written for: a
     * Kafka listener whose whole unit of work - the request row, its fan-out, the claim - is one
     * transaction. A claim that committed independently would leave a key permanently reserved for a
     * request whose transaction then rolled back, and the retry of that request would be rejected as
     * a duplicate of something that does not exist.
     *
     * <p>The consequence is that a visible claim always means committed work, which is why there is
     * no {@code IN_PROGRESS} state in this mode and nothing to lease: an uncommitted claim is
     * invisible to everyone else by construction, and a concurrent duplicate blocks on the winner's
     * row lock rather than being told "in flight".
     *
     * <p>Requires an active transaction. A store asked for this mode with none open fails loudly
     * rather than silently degrading, because degrading would mean committing the claim
     * independently, which is the exact failure the mode exists to prevent.
     */
    TRANSACTIONAL,

    /**
     * The claim commits on its own, and runs a state machine the caller drives.
     *
     * <p>The mode an HTTP filter needs, and any consumer whose work spans more than one transaction
     * or calls out to somebody else. The work cannot be one transaction, so the claim cannot ride
     * along with it: the claim is committed first as {@code IN_PROGRESS}, and the caller reports the
     * outcome afterwards - {@code COMPLETED} with the response to replay, or {@code FAILED}.
     *
     * <p>{@code IN_PROGRESS} carries a lease for the same reason {@code RunLock} does. A process that
     * dies mid-request would otherwise leave the key {@code IN_PROGRESS} forever, and a key stuck in
     * progress is a caller who can never retry - which is worse than the double execution the claim
     * was protecting against, because it is permanent. A holder that stops renewing stops being the
     * holder.
     *
     * <p>A {@code FAILED} claim is immediately reclaimable. A failed attempt must not block the retry
     * it exists to enable.
     */
    STANDALONE
}
