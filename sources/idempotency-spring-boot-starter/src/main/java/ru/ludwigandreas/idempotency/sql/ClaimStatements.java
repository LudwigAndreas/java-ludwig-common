package ru.ludwigandreas.idempotency.sql;

/**
 * The conditional upsert that makes this module correct, and nothing else.
 *
 * <h2>Why QueryDSL cannot express this</h2>
 *
 * <p>JPQL has neither {@code ON CONFLICT} nor {@code RETURNING}, and QueryDSL-JPA generates JPQL. The
 * whole value of this statement is that it is <em>one</em> statement: it inserts or, on a conflict,
 * either takes an abandoned claim over or reports the live holder - and it decides which inside the
 * statement, so there is no window between deciding and acting for a second process to fit into. Split
 * into a select and an insert it is not slower, it is wrong; see this package's documentation.
 *
 * <h2>Why {@code DO UPDATE} and not {@code DO NOTHING}</h2>
 *
 * <p>{@code DO NOTHING} returns no row on conflict, so the loser of a race would have to re-select -
 * and in {@code READ COMMITTED} a re-select can still miss a row whose inserting transaction has not
 * committed, which is precisely the concurrent case this exists for. The {@code DO UPDATE} below makes
 * the loser block on the winner's row lock and then read the <em>committed</em> winner: no exception,
 * no aborted transaction, no double execution.
 *
 * <p>This is the subtlety somebody will eventually try to simplify away, because on a single-threaded
 * test {@code DO NOTHING} plus a re-select passes.
 *
 * <h2>Why one statement serves both claim modes</h2>
 *
 * <p>The two {@code ClaimMode}s differ in what a fresh claim <em>inserts</em> - a transactional claim
 * inserts {@code COMPLETED} with no lease, a standalone one inserts {@code IN_PROGRESS} with a lease -
 * and in the transaction the store runs the statement in. They do not differ in how a conflict is
 * resolved: a key whose window has passed, whose holder's lease has expired, or which was reported
 * failed is free, and it is equally free whichever mode freed it. Writing the conflict rule twice would
 * mean maintaining two copies of the one part of this module that has to be exactly right.
 */
final class ClaimStatements {

    /** The table. A constant because three statements name it and a typo in one of them is silent. */
    static final String TABLE = "idempotency_claim";

    /**
     * When an existing claim may be taken over by the caller.
     *
     * <p>Three ways a claim stops belonging to its holder, and each one is a failure this module would
     * otherwise turn into a permanent outage for one key:
     *
     * <ul>
     *   <li><b>its window passed</b> - evaluated here rather than left to the purge. The purge is
     *       asynchronous and runs on one replica on a schedule, so a claim can be minutes or hours past
     *       its TTL and still present. Deciding expiry in the statement is what makes the TTL mean what
     *       the configuration says it means, instead of "the TTL, plus however long until the purge next
     *       ran";</li>
     *   <li><b>it was reported failed</b> - a failed attempt must not block the retry it exists to
     *       enable;</li>
     *   <li><b>its holder stopped renewing</b> - a process that died mid-request would otherwise leave
     *       the key in progress forever, and a key stuck in progress is a caller who can never retry.</li>
     * </ul>
     */
    private static final String RECLAIMABLE = """
            t.expires_at <= :now
                OR t.state = 'FAILED'
                OR (t.state = 'IN_PROGRESS' AND t.lease_expires_at <= :now)""";

    /** Every column the claim row has, in the order the insert names them. */
    private static final String COLUMNS = """
            id, scope, idempotency_key, request_id, state, fingerprint, lease_expires_at,
            failure_reason, response_status, response_content_type, response_headers, response_body,
            created_at, expires_at""";

    /**
     * Everything the statement reads back.
     *
     * <p>The same set whether the caller won or lost, so there is one row mapper and no branch on which
     * it was: the caller decides that by comparing {@code request_id} to its own, which is the only
     * signal that cannot be wrong.
     */
    private static final String RETURNED = """
            request_id, state, fingerprint, lease_expires_at, expires_at,
            response_status, response_content_type, response_headers, response_body""";

    /**
     * Claim {@code (scope, idempotency_key)}, taking over an abandoned claim or reporting the holder.
     *
     * <p>Every assignment is the same shape - take the new value if the old claim was abandoned, keep
     * the old one otherwise - which is what makes "leaves a live claim untouched" true of the whole row
     * rather than of the columns somebody remembered. In particular a live claim's stored response, its
     * fingerprint and its {@code created_at} all survive a losing claim attempt, and a reclaim clears
     * the previous holder's response instead of leaving a body that belongs to different work.
     *
     * <p>{@code expires_at} is deliberately <em>not</em> extended on a losing attempt. Extending the
     * window on every duplicate would mean a key that is retried forever never expires, and the table
     * would grow without bound on exactly the traffic pattern it is meant to absorb.
     */
    static final String CLAIM = """
            INSERT INTO %s AS t (%s)
            VALUES (:id, :scope, :key, :requestId, :state, :fingerprint, :leaseExpiresAt,
                    NULL, NULL, NULL, NULL, NULL,
                    :now, :expiresAt)
            ON CONFLICT (scope, idempotency_key) DO UPDATE SET
                request_id = %s,
                state = %s,
                fingerprint = %s,
                lease_expires_at = %s,
                failure_reason = %s,
                response_status = %s,
                response_content_type = %s,
                response_headers = %s,
                response_body = %s,
                created_at = %s,
                expires_at = %s
            RETURNING %s
            """.formatted(TABLE, COLUMNS,
            keepUnlessReclaimable("request_id"),
            keepUnlessReclaimable("state"),
            keepUnlessReclaimable("fingerprint"),
            keepUnlessReclaimable("lease_expires_at"),
            keepUnlessReclaimable("failure_reason"),
            keepUnlessReclaimable("response_status"),
            keepUnlessReclaimable("response_content_type"),
            keepUnlessReclaimable("response_headers"),
            keepUnlessReclaimable("response_body"),
            keepUnlessReclaimable("created_at"),
            keepUnlessReclaimable("expires_at"),
            RETURNED);

    /**
     * One column's conflict assignment.
     *
     * <p>Generated rather than written out eleven times. A hand-written list of eleven near-identical
     * {@code CASE} expressions is where the one that says {@code t.request_id} instead of
     * {@code EXCLUDED.request_id} hides, and that particular typo hands the key to nobody and lets both
     * callers do the work.
     */
    private static String keepUnlessReclaimable(String column) {
        return "CASE WHEN " + RECLAIMABLE + " THEN EXCLUDED." + column + " ELSE t." + column + " END";
    }

    private ClaimStatements() {
    }
}
