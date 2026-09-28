/**
 * The one place in this module where SQL is written by hand, and the documented carve-out from the
 * repository's QueryDSL-only rule.
 *
 * <h2>What the exception is for</h2>
 *
 * <p>{@code CLAUDE.md} mandates QueryDSL against generated Q-types only - no JPQL, no SQL strings - and
 * this module needs an exception for the statements that make it correct. There are two, both
 * conditional upserts, and neither is expressible in QueryDSL or in JPQL:
 *
 * <ol>
 *   <li>the transactional claim: {@code INSERT ... ON CONFLICT (scope, idempotency_key) DO UPDATE ...
 *       RETURNING request_id};</li>
 *   <li>the standalone claim, which is the same statement plus a reclaim: on conflict it either takes
 *       the row over - because its window passed, its holder's lease expired, or it was reported failed -
 *       or leaves it untouched and returns the holder, and it decides which in the statement.</li>
 * </ol>
 *
 * <p>JPQL has no {@code ON CONFLICT} and no {@code RETURNING}; QueryDSL-JPA generates JPQL. QueryDSL-SQL
 * would be a second query engine, a second set of generated types and a second dialect story for one
 * statement per mode. And the alternative shapes are not merely less tidy, they are wrong:
 *
 * <ul>
 *   <li><b>Read-then-insert</b> is the obvious implementation and it is exactly wrong for the case this
 *       module exists to handle. Two replicas processing the same at-least-once record both read
 *       "no row", both insert, and one takes a constraint violation that aborts a transaction which has
 *       by then already written a request and its deliveries. It also fails silently: under low load it
 *       looks perfect.</li>
 *   <li><b>{@code DO NOTHING}</b> returns no row on conflict, so the loser has to re-select - and in
 *       {@code READ COMMITTED} a re-select can still miss a row whose inserting transaction has not
 *       committed. The no-op {@code DO UPDATE} exists purely to make the statement return the existing
 *       row, which makes the loser block on the winner's row lock and then read the committed winner.
 *       No exception, no rollback, no double send. This is the subtlety somebody will eventually try to
 *       "simplify", which is why it is written out here and again on each statement.</li>
 * </ul>
 *
 * <h2>The conditions the carve-out is granted under</h2>
 *
 * <p>The same three {@code ru.ludwigandreas.ingest.bulk} operates under, because an exception with no
 * boundary is not an exception, it is a repeal:
 *
 * <ol>
 *   <li>every SQL string in this module stays in this package;</li>
 *   <li>each statement carries documentation saying why QueryDSL cannot express it;</li>
 *   <li>{@code SqlConfinementTest} fails the build if SQL or JDBC appears anywhere else in the module.</li>
 * </ol>
 *
 * <p>Everything else - the read side, the purge, the operator queries - is a QueryDSL predicate in
 * {@code ru.ludwigandreas.idempotency.repository}, against generated Q-types, like the rest of the
 * platform.
 *
 * <h2>Why JDBC rather than a native {@code @Query}</h2>
 *
 * <p>A Spring Data {@code @Query(nativeQuery = true)} would put the SQL in the repository package, which
 * breaks condition (1) for no gain. Plain JDBC on the caller's transaction-bound connection also makes
 * the transactional mode's contract explicit: the statement runs in whatever transaction the caller
 * opened, and {@code DataSourceUtils} is what binds it there.
 */
package ru.ludwigandreas.idempotency.sql;
