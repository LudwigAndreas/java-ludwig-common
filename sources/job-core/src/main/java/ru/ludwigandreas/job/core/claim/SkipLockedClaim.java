package ru.ludwigandreas.job.core.claim;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds and executes the one query shape every poller in this platform is made of: atomically claim
 * up to N due rows of a work table for this instance, skipping rows another instance already holds.
 *
 * <h2>The shape, and why it is this shape</h2>
 *
 * <pre>{@code
 * UPDATE {table}
 *    SET {claim assignments}
 *  WHERE id IN (SELECT id FROM {table} t
 *                WHERE {due predicate}
 *                ORDER BY {order}
 *                LIMIT :jobClaimLimit
 *                FOR UPDATE SKIP LOCKED)
 * RETURNING *
 * }</pre>
 *
 * <p>Three properties of that statement are load-bearing, and all three are lost if it is rewritten
 * in a more obvious way:
 *
 * <ul>
 *   <li><b>{@code FOR UPDATE SKIP LOCKED} in a subquery, not the outer update.</b> Postgres cannot
 *       attach a locking clause to an {@code UPDATE}; the row locks an update takes are blocking. The
 *       subquery is what makes a second poller step over rows the first one is claiming instead of
 *       queueing behind them, which is the difference between N instances sharing the work and N
 *       instances taking turns.</li>
 *   <li><b>{@code RETURNING *} rather than a second {@code SELECT}.</b> Claiming and reading in one
 *       round trip removes the window in which a row is claimed but not yet loaded, and halves the
 *       statements on the hottest path in the system. It is also why this executes as a
 *       <em>result-set-returning</em> query: an {@code executeUpdate()} would discard the returned
 *       rows and leave the caller with a count it cannot act on.</li>
 *   <li><b>A deterministic {@code ORDER BY}.</b> Without one, Postgres is free to return due rows in
 *       any order, so a backlog can starve its own oldest entries indefinitely - the failure looks
 *       like "most things are fine but a few records are days stale", which is very hard to attribute
 *       to a missing sort.</li>
 * </ul>
 *
 * <h2>Why this takes SQL fragments rather than a typed criteria API</h2>
 *
 * <p>The locking clause has no JPQL or Criteria equivalent that survives into the generated SQL in
 * this form, so the statement is native either way. Callers pass fragments they wrote themselves and
 * bind every value as a parameter; nothing user-supplied is ever interpolated into the string. The
 * table name and fragments come from module code, never from configuration or a request.
 */
public final class SkipLockedClaim {

    /** Parameter name the generated statement binds the batch size to. */
    public static final String LIMIT_PARAMETER = "jobClaimLimit";

    private SkipLockedClaim() {
    }

    /**
     * Renders the claim statement.
     *
     * @param table          physical table name, written by module code and never by configuration
     * @param claimSetClause the {@code SET} body that marks a row as claimed, for example
     *                       {@code "status = 'PROCESSING', locked_at = now(), locked_by = :owner"}
     * @param duePredicate   the {@code WHERE} body selecting claimable rows, addressing the table as
     *                       {@code t}, for example {@code "t.status = 'PENDING' AND t.next_attempt_at <= :now"}
     * @param orderBy        the {@code ORDER BY} body, also addressing the table as {@code t}; must be
     *                       total enough to give a stable claim order, which in practice means ending
     *                       in the primary key
     * @return native SQL with named parameters, including {@value #LIMIT_PARAMETER}
     */
    public static String sql(String table, String claimSetClause, String duePredicate, String orderBy) {
        return sql(table, claimSetClause, duePredicate, orderBy, ":" + LIMIT_PARAMETER);
    }

    /**
     * Renders the claim statement with an explicit limit expression.
     *
     * <p>The overload exists because the two ways this platform executes native SQL bind parameters
     * differently: JPA takes {@code :name} placeholders, plain JDBC takes {@code ?}. Rather than have
     * one of them rewrite the other's string - the kind of ad-hoc placeholder translation that
     * eventually mangles a literal - each caller says which form its own fragments are written in.
     *
     * @param table           physical table name
     * @param claimSetClause  see {@link #sql(String, String, String, String)}
     * @param duePredicate    see {@link #sql(String, String, String, String)}
     * @param orderBy         see {@link #sql(String, String, String, String)}
     * @param limitExpression the placeholder or literal the {@code LIMIT} clause uses, for example
     *                        {@code ":jobClaimLimit"}, {@code "?"} or {@code "1"}
     * @return native SQL in the placeholder style the caller asked for
     */
    public static String sql(String table,
                             String claimSetClause,
                             String duePredicate,
                             String orderBy,
                             String limitExpression) {
        return "UPDATE " + table + " SET " + claimSetClause
                + " WHERE id IN (SELECT t.id FROM " + table + " t"
                + " WHERE " + duePredicate
                + " ORDER BY " + orderBy
                + " LIMIT " + limitExpression
                + " FOR UPDATE SKIP LOCKED)"
                + " RETURNING *";
    }

    /**
     * Renders {@link #sql} and executes it as an entity query, hydrating the claimed rows.
     *
     * <p>The caller supplies {@code parameters} for every named binding its own fragments introduced;
     * the limit is bound here. The returned entities are managed by {@code entityManager}, so this
     * must run inside a transaction - the claim and the read are the same statement, and committing
     * the claim while the caller still believes it holds unflushed changes to those rows is the one
     * way to lose an outcome.
     *
     * @param <T>           the entity type mapped to {@code table}
     * @param entityManager the entity manager to execute against, inside an active transaction
     * @param entityType    entity class the returned rows are hydrated into
     * @param table         physical table name
     * @param claimSetClause see {@link #sql}
     * @param duePredicate  see {@link #sql}
     * @param orderBy       see {@link #sql}
     * @param limit         maximum number of rows to claim in this call
     * @param parameters    named bindings referenced by the supplied fragments
     * @return the rows this call claimed, in {@code orderBy} order
     */
    @SuppressWarnings("unchecked")
    public static <T> List<T> claim(EntityManager entityManager,
                                    Class<T> entityType,
                                    String table,
                                    String claimSetClause,
                                    String duePredicate,
                                    String orderBy,
                                    int limit,
                                    Map<String, Object> parameters) {
        Query query = entityManager.createNativeQuery(
                sql(table, claimSetClause, duePredicate, orderBy), entityType);
        Map<String, Object> bindings = new LinkedHashMap<>(parameters);
        bindings.put(LIMIT_PARAMETER, limit);
        bindings.forEach(query::setParameter);
        return query.getResultList();
    }
}
