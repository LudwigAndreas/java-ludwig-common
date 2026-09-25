package ru.ludwigandreas.ingest.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ContextConfiguration;
import ru.ludwigandreas.ingest.bulk.StagingWriter;
import ru.ludwigandreas.ingest.engine.IngestPass;

/**
 * The batch and the checkpoint roll back together.
 *
 * <h2>Why this test exists when {@code CheckpointResumeIT} already passes</h2>
 *
 * <p>Resumption does not prove atomicity. If {@code BatchCommitter}'s {@code @Transactional} were a
 * no-op - the annotation removed, the bean not proxied, the propagation changed by somebody tidying
 * up - every write would still happen, each on its own auto-commit, and the resume test would pass
 * exactly as it does now. What would change is invisible from outside until a run is interrupted at
 * precisely the wrong moment, at which point the staged rows and the checkpoint disagree and the
 * module has silently lost or duplicated records.
 *
 * <p>So this test makes the staging write succeed and then fails the transaction, and asserts that
 * <em>both</em> halves went away: nothing staged, and the checkpoint still at zero. Without the
 * transaction the rows would be committed and the checkpoint would not be - which is the "data first,
 * then checkpoint" failure that produces duplicates on the next run.
 *
 * <p>This is the test {@code BatchCommitter}'s documentation points at when it says not to split that
 * method up. Splitting it is the change that would break this and nothing else.
 */
@ContextConfiguration(classes = CheckpointAtomicityIT.FailingWriterConfiguration.class)
class CheckpointAtomicityIT extends FileIngestTestBase {

    @Autowired
    private IngestPass pass;

    @Autowired
    private FailAfterWriteStagingWriter writer;

    @Test
    @DisplayName("a batch that is written and then fails leaves nothing staged and no checkpoint")
    void theBatchAndTheCheckpointRollBackTogether() {
        put(dropKey("catalogue-atomic.csv"), """
                SKU-1,widget,100
                SKU-2,gadget,200
                SKU-3,gizmo,300
                """);
        writer.failAfterWriting(true);

        assertThatThrownBy(() -> pass.runOnce(CatalogueIngest.TASK))
                .isInstanceOf(RuntimeException.class);

        assertThat(writer.wroteRows())
                .as("the rows must actually have reached the staging writer, or this test is asserting"
                        + " that nothing happened rather than that it was rolled back")
                .isTrue();
        assertThat(jdbc().queryForObject("SELECT count(*) FROM staging_catalogue", Long.class))
                .as("the staged rows must have rolled back with the failed checkpoint. If they are"
                        + " here, BatchCommitter.commit is no longer one transaction, and an"
                        + " interrupted run will duplicate every record of its last batch.")
                .isZero();
        assertThat(jdbc().queryForObject(
                "SELECT checkpoint_position FROM file_ingest_run", Long.class))
                .as("and the checkpoint must not have advanced past data that is not there")
                .isZero();
        assertThat(jdbc().queryForObject("SELECT count(*) FROM catalogue", Long.class)).isZero();
    }

    @Test
    @DisplayName("with the writer behaving, the same file commits both halves")
    void theSameBatchCommitsBothHalvesWhenNothingFails() {
        put(dropKey("catalogue-atomic2.csv"), "SKU-1,widget,100\nSKU-2,gadget,200\n");
        writer.failAfterWriting(false);

        pass.runOnce(CatalogueIngest.TASK);

        assertThat(jdbc().queryForObject("SELECT count(*) FROM catalogue", Long.class)).isEqualTo(2);
        assertThat(jdbc().queryForObject(
                "SELECT records_committed FROM file_ingest_run", Long.class)).isEqualTo(2);
        assertThat(jdbc().queryForObject(
                "SELECT checkpoint_position FROM file_ingest_run", Long.class)).isPositive();
    }

    /** Replaces the staging writer with one that can fail after the rows have been written. */
    @TestConfiguration
    static class FailingWriterConfiguration {

        /**
         * The decorated writer.
         *
         * @param dataSource the application's data source, so the delegate is the real thing
         * @return the decorator
         */
        @Bean
        @Primary
        FailAfterWriteStagingWriter failAfterWriteStagingWriter(javax.sql.DataSource dataSource) {
            return new FailAfterWriteStagingWriter(
                    new ru.ludwigandreas.ingest.bulk.CopyStagingWriter(dataSource));
        }
    }

    /**
     * Writes the rows through the real writer, then throws.
     *
     * <p>The order matters: throwing <em>before</em> writing would prove only that a failed write
     * leaves nothing behind, which is uninteresting. Writing first and then failing is what puts the
     * rows inside a transaction that is about to roll back.
     */
    static class FailAfterWriteStagingWriter implements StagingWriter {

        private final StagingWriter delegate;
        private volatile boolean fail;
        private volatile boolean wrote;

        FailAfterWriteStagingWriter(StagingWriter delegate) {
            this.delegate = delegate;
        }

        void failAfterWriting(boolean shouldFail) {
            this.fail = shouldFail;
            this.wrote = false;
        }

        boolean wroteRows() {
            return wrote;
        }

        @Override
        public int write(String table, List<String> columns, List<Object[]> rows) {
            int written = delegate.write(table, columns, rows);
            wrote = wrote || written > 0;
            if (fail) {
                throw new IllegalStateException("simulated failure after " + written
                        + " staged row(s), before the checkpoint could be advanced");
            }
            return written;
        }

        @Override
        public void truncate(String table) {
            delegate.truncate(table);
        }
    }
}
