package ru.ludwigandreas.ingest.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import ru.ludwigandreas.ingest.api.IngestRunStatus;
import ru.ludwigandreas.ingest.engine.IngestPass;

/**
 * Nothing is read until the partner says the object is finished.
 *
 * <p>This is the failure that does not look like one: reading a half-written object produces a
 * <em>successful</em> run with a truncated tail, which balances perfectly - everything it read was
 * accounted for - and which nothing else in the module can detect. The sentinel is the only thing
 * standing between a daily ingest and that, which is why it is on by default and why its absence is
 * asserted as "nothing was read at all" rather than "the run did not complete".
 */
@TestPropertySource(properties = {
        "ludwig.ingest.tasks.partner-catalogue.arrival.sentinel={name}.done",
        "ludwig.ingest.tasks.partner-catalogue.arrival.expected-count-field=recordCount"
})
class SentinelArrivalIT extends FileIngestTestBase {

    @Autowired
    private IngestPass pass;

    @Test
    @DisplayName("an object with no sentinel is not read at all")
    void doesNotReadWithoutASentinel() {
        put(dropKey("catalogue-s1.csv"), "SKU-1,widget,100\n");

        pass.runOnce(CatalogueIngest.TASK);

        // No run row, not a failed one: the object was never opened, so there is nothing to record.
        // Asserting on the run count rather than on the target is what makes this test about arrival
        // rather than about the merge.
        assertThat(jdbc().queryForObject("SELECT count(*) FROM file_ingest_run", Long.class)).isZero();
        assertThat(jdbc().queryForObject("SELECT count(*) FROM catalogue", Long.class)).isZero();
    }

    @Test
    @DisplayName("the same object is read once its sentinel arrives")
    void readsOnceTheSentinelArrives() {
        put(dropKey("catalogue-s2.csv"), "SKU-1,widget,100\n");
        pass.runOnce(CatalogueIngest.TASK);
        assertThat(jdbc().queryForObject("SELECT count(*) FROM file_ingest_run", Long.class)).isZero();

        put(dropKey("catalogue-s2.csv.done"), "");
        pass.runOnce(CatalogueIngest.TASK);

        assertThat(jdbc().queryForObject("SELECT status FROM file_ingest_run", String.class))
                .isEqualTo(IngestRunStatus.COMPLETED.name());
        assertThat(jdbc().queryForObject("SELECT count(*) FROM catalogue", Long.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("the sentinel is not itself ingested, however loose the pattern is")
    void doesNotIngestTheSentinel() {
        put(dropKey("catalogue-s3.csv"), "SKU-1,widget,100\n");
        put(dropKey("catalogue-s3.csv.done"), "");

        pass.runOnce(CatalogueIngest.TASK);

        // One run, for the data object. Ingesting the sentinel would produce a second run that read a
        // zero-byte object, applied nothing, balanced perfectly and completed - and the identity
        // constraint would then remember it, making the mistake permanent.
        assertThat(jdbc().queryForObject(
                "SELECT count(*) FROM file_ingest_run", Long.class)).isEqualTo(1);
        assertThat(jdbc().queryForObject(
                "SELECT source_uri FROM file_ingest_run", String.class)).endsWith("catalogue-s3.csv");
    }

    @Test
    @DisplayName("a sentinel declaring a record count that does not match fails the run")
    void failsWhenTheFileIsShorterThanItsSentinelSays() {
        put(dropKey("catalogue-s4.csv"), "SKU-1,widget,100\nSKU-2,gadget,200\n");
        // The file holds two records and the partner says it sent three: that is what a truncated
        // upload which happened to end on a record boundary looks like, and the sentinel's count is
        // the only number in the system that comes from outside the run and can detect it.
        put(dropKey("catalogue-s4.csv.done"), "{\"recordCount\": 3}");

        try {
            pass.runOnce(CatalogueIngest.TASK);
        } catch (RuntimeException e) {
            // The run fails loudly; the scheduler is what logs it in production.
            assertThat(e).hasMessageContaining("sentinel declared 3");
        }

        assertThat(jdbc().queryForObject("SELECT status FROM file_ingest_run", String.class))
                .isEqualTo(IngestRunStatus.FAILED.name());
        assertThat(jdbc().queryForObject("SELECT count(*) FROM catalogue", Long.class))
                .as("the target must be untouched: the balance check runs before the merge")
                .isZero();
    }

    @Test
    @DisplayName("a sentinel declaring a matching count completes")
    void completesWhenTheCountMatches() {
        put(dropKey("catalogue-s5.csv"), "SKU-1,widget,100\nSKU-2,gadget,200\n");
        put(dropKey("catalogue-s5.csv.done"), "{\"recordCount\": 2}");

        pass.runOnce(CatalogueIngest.TASK);

        assertThat(jdbc().queryForObject("SELECT status FROM file_ingest_run", String.class))
                .isEqualTo(IngestRunStatus.COMPLETED.name());
    }
}
