/**
 * The one package in this module allowed to contain SQL, and the boundary of a deliberate exception
 * to a standing repository rule.
 *
 * <h2>The rule, and why it is suspended here</h2>
 *
 * <p>{@code CLAUDE.md} mandates QueryDSL against generated Q-types only - no JPQL, no SQL strings -
 * and everything else in this module obeys it. Two statements cannot be written that way, and both
 * are load-bearing:
 *
 * <ul>
 *   <li><b>Postgres {@code COPY}</b>, through {@code CopyManager}. It is the reason to want this
 *       exception at all: it streams rows into the staging table and is several times faster than
 *       batched {@code INSERT} at the row counts this module is built for. QueryDSL has no
 *       expression for it, and neither does JPA.</li>
 *   <li><b>A set-based upsert</b>, {@code INSERT INTO target SELECT ... FROM staging ON CONFLICT DO
 *       UPDATE}. QueryDSL's JPA module cannot express an insert-from-select at all, let alone one
 *       with a conflict clause. The alternative is to read four million staged rows into this
 *       process and write them back one at a time, which is precisely the behaviour the module
 *       exists to avoid.</li>
 * </ul>
 *
 * <h2>The conditions the exception was granted under</h2>
 *
 * <ol>
 *   <li>Every SQL string in this module is in this package and nowhere else.</li>
 *   <li>Each statement carries javadoc explaining why QueryDSL cannot express it - the same standard
 *       {@code DistributedLockRepository} and {@code IdempotencyRecordRepository} meet today for
 *       their native queries.</li>
 *   <li>An ArchUnit rule in this module's own test suite fails the build if SQL appears outside this
 *       package, so the exception cannot quietly spread.</li>
 *   <li>The carve-out and its boundary are recorded in {@code CLAUDE.md}'s "Module conventions".</li>
 * </ol>
 *
 * <p>The rule the exception does not touch: this module's own tables - {@code file_ingest_run} and
 * {@code file_ingest_quarantine} - are queried through QueryDSL predicates in
 * {@code ru.ludwigandreas.ingest.repository}, exactly as everything else in the platform is. The
 * exception is about the <em>bulk path into the author's schema</em>, not about this module's
 * persistence.
 */
package ru.ludwigandreas.ingest.bulk;
