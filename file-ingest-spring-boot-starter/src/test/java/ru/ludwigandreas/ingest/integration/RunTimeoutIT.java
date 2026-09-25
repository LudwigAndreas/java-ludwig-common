package ru.ludwigandreas.ingest.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import ru.ludwigandreas.ingest.api.IngestRunStatus;
import ru.ludwigandreas.ingest.engine.IngestPass;
import ru.ludwigandreas.ingest.exception.RunTimeoutException;

/**
 * A run that outlasts its budget stops, and stops where the next one can pick up.
 *
 * <p>Tested because a configured property that nothing enforces is worse than no property: it makes an
 * operator believe there is a guard where there is not one. The budget is set to zero here, so the
 * check fires after the first batch - which is also the cheapest possible way to assert that it is
 * checked between batches rather than only at the top.
 */
@TestPropertySource(properties = {
        "ludwig.ingest.tasks.partner-catalogue.schedule.run-timeout=0s",
        "ludwig.ingest.tasks.partner-catalogue.lock.lease=0s",
        "ludwig.ingest.tasks.partner-catalogue.batch.max-records=1"
})
class RunTimeoutIT extends FileIngestTestBase {

    @Autowired
    private IngestPass pass;

    @Test
    @DisplayName("a run past its budget stops, keeping the checkpoint it reached")
    void stopsAndKeepsItsCheckpoint() {
        put(dropKey("catalogue-timeout.csv"), """
                SKU-1,widget,100
                SKU-2,gadget,200
                SKU-3,gizmo,300
                """);

        assertThatThrownBy(() -> pass.runOnce(CatalogueIngest.TASK))
                .isInstanceOf(RunTimeoutException.class)
                .hasMessageContaining("schedule.run-timeout");

        assertThat(jdbc().queryForObject("SELECT status FROM file_ingest_run", String.class))
                .isEqualTo(IngestRunStatus.FAILED.name());
        // The point of stopping rather than failing hard: the work already committed is kept, so the
        // next run resumes from it. A timeout that discarded the checkpoint would make a large file
        // permanently un-ingestable under a budget slightly too small for it.
        assertThat(jdbc().queryForObject(
                "SELECT records_committed FROM file_ingest_run", Long.class)).isPositive();
        assertThat(jdbc().queryForObject(
                "SELECT checkpoint_position FROM file_ingest_run", Long.class)).isPositive();
    }
}
