package ru.ludwigandreas.ingest.bulk;

import java.util.List;

/**
 * Puts a batch of rows into a staging table.
 *
 * <p>An interface with two implementations so that the module is fast on Postgres without being
 * Postgres-only by accident. {@code CopyStagingWriter} uses {@code COPY} and is several times faster;
 * {@code JdbcBatchStagingWriter} uses batched {@code INSERT} and works anywhere. Which one is used is
 * {@code ludwig.ingest.tasks.<name>.write.staging-writer}, whose default picks {@code COPY} when the
 * connection is Postgres and falls back otherwise - so a service gets the fast path without having to
 * know whether it qualifies.
 *
 * <p>Implementations are called inside the engine's checkpoint transaction and must not manage a
 * transaction of their own. See {@code IngestRunner} for why that transaction is one transaction.
 */
public interface StagingWriter {

    /**
     * Writes one batch.
     *
     * @param table   the staging table, as {@code RecordApplier#stagingTable()} names it
     * @param columns the columns being written, in the order the rows supply them
     * @param rows    the rows; each row has one value per column, in that order
     * @return how many rows were written
     */
    int write(String table, List<String> columns, List<Object[]> rows);

    /**
     * Empties the staging table before a run that starts from scratch.
     *
     * <p>Called only when the checkpoint is at zero. A resumed run must <em>not</em> truncate: the
     * rows already in staging are exactly the work the committed checkpoint says has been done, and
     * removing them would lose every record before the resume point while the checkpoint went on
     * claiming they were written. That is the one way this module could lose data without any
     * individual step being wrong, which is why the decision is made by the engine from the
     * checkpoint rather than by configuration.
     *
     * @param table the staging table
     */
    void truncate(String table);
}
