package ru.ludwigandreas.ingest.api;

import java.util.List;

/**
 * Records to rows. The other half of what the author of an ingest writes.
 *
 * <h2>The author maps one record; the module writes the batch</h2>
 *
 * <p>The obvious shape for this interface is {@code void apply(List<R> batch)} - hand the author the
 * batch and let them write it. It is the wrong shape here for two reasons, and both are about what
 * the module would lose.
 *
 * <p>First, the bulk path. Whether a batch goes in through Postgres {@code COPY} or through batched
 * {@code INSERT} is the module's decision, made from what the connection turns out to be, and the
 * whole reason the SQL carve-out in {@code ru.ludwigandreas.ingest.bulk} was granted. An applier that
 * wrote its own rows would take that decision away from the module and would, in practice, write them
 * one at a time - which is several times slower and is what the carve-out exists to avoid.
 *
 * <p>Second, per-record isolation. When the author maps one record at a time, a record that cannot be
 * mapped is <em>one</em> record: it becomes a quarantine row and the other four thousand nine hundred
 * and ninety-nine in the batch are written. When the author writes the batch, a failure anywhere in it
 * is a failure of all of it, and the module has no way to find out which record was responsible short
 * of retrying the batch a record at a time.
 *
 * <h2>Staging, not the target</h2>
 *
 * <p>The rows go into a <em>staging</em> table, and the engine merges staging into the target once, at
 * the end, with {@link #mergeStatement()}. That split buys three things: the parse phase runs free of
 * the target's constraints, so one violating row does not abort a transaction carrying five thousand
 * good ones; the target changes atomically, so nothing ever observes a half-imported catalogue; and
 * the row counts are checkable, which is what the balance check is built on.
 *
 * @param <R> the record type {@link RecordParser} produces
 */
public interface RecordApplier<R> {

    /**
     * The staging table rows are written into.
     *
     * <p>Named here rather than configured, because the engine truncates it before a fresh run and
     * merges from it at the end - and a name that lived in configuration could disagree with the one
     * {@link #mergeStatement()} reads from. The symptom of that disagreement is a run that reports
     * four million records applied and changes nothing.
     *
     * @return the unqualified table name; must be a plain unquoted SQL identifier
     */
    String stagingTable();

    /**
     * The staging columns, in the order {@link #toRow} supplies their values.
     *
     * @return the column names; each must be a plain unquoted SQL identifier
     */
    List<String> columns();

    /**
     * Turns one record into one staging row.
     *
     * <p>Called on the run's own thread, inside the transaction that also advances the checkpoint, so
     * it must not open a transaction, must not call a partner, and must not do anything that survives
     * a rollback - whatever it does that a rollback cannot undo will be done twice.
     *
     * @param record the record
     * @return the values, one per entry of {@link #columns()}, or {@code null} to drop the record
     *         deliberately - a duplicate key within the same file, say. A dropped record counts as
     *         <em>skipped</em> in the balance check rather than vanishing from it.
     * @throws RecordApplyException if this one record cannot be mapped; it is quarantined with its
     *                              offset and the rest of the batch is written
     */
    Object[] toRow(R record);

    /**
     * The statement that merges staging into the target, run once at the end of a successful run.
     *
     * <p>Written by the author because only the author knows the target's shape, its conflict key and
     * which of its columns an existing row should take from the new one. It is SQL, and this is one
     * of the two places in the platform where SQL is written by hand - see
     * {@code ru.ludwigandreas.ingest.bulk} and the carve-out recorded in {@code CLAUDE.md}. QueryDSL
     * cannot express {@code INSERT ... SELECT ... ON CONFLICT DO UPDATE}, and the alternative -
     * reading four million staged rows into this process to write them back one at a time - is the
     * behaviour this whole module exists to avoid.
     *
     * <p>Takes no parameters. The engine passes none and interpolates nothing into it.
     *
     * @return an {@code INSERT INTO target (...) SELECT ... FROM staging ... ON CONFLICT ... DO
     *         UPDATE} statement
     */
    String mergeStatement();
}
