package ru.ludwigandreas.ingest.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.ludwigandreas.ingest.api.IngestRunStatus;
import ru.ludwigandreas.ingest.engine.IngestPass;
import ru.ludwigandreas.ingest.exception.QuarantineThresholdExceededException;

/**
 * The ordinary path, and the cases that make the identity triple the exactly-once guard.
 */
class FileIngestLifecycleIT extends FileIngestTestBase {

    @Autowired
    private IngestPass pass;

    @Test
    @DisplayName("a whole file is ingested and merged into the target")
    void ingestsAFile() {
        put(dropKey("catalogue-a.csv"), "SKU-1,widget,100\nSKU-2,gadget,250\n");

        assertThat(pass.runOnce(CatalogueIngest.TASK)).isTrue();

        assertThat(jdbc().queryForObject("SELECT count(*) FROM catalogue", Long.class)).isEqualTo(2);
        Map<String, Object> run = jdbc().queryForMap(
                "SELECT status, records_read, records_applied, records_quarantined, records_skipped"
                        + " FROM file_ingest_run");
        assertThat(run.get("status")).isEqualTo(IngestRunStatus.COMPLETED.name());
        assertThat(run.get("records_read")).isEqualTo(2L);
        assertThat(run.get("records_applied")).isEqualTo(2L);
    }

    @Test
    @DisplayName("the same object offered again is skipped, and the identity triple is why")
    void skipsAnObjectItHasAlreadyIngested() {
        put(dropKey("catalogue-b.csv"), "SKU-1,widget,100\n");
        pass.runOnce(CatalogueIngest.TASK);

        pass.runOnce(CatalogueIngest.TASK);

        // One run, not two: the second pass found a COMPLETED run for (container, key, etag).
        assertThat(jdbc().queryForObject("SELECT count(*) FROM file_ingest_run", Long.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the same NAME with different content is a new run, which is the point of the etag")
    void reIngestsACorrectedFileUnderTheSameName() {
        String key = dropKey("catalogue-c.csv");
        put(key, "SKU-1,widget,100\n");
        pass.runOnce(CatalogueIngest.TASK);

        // A correction: same name, same length, one character different. Keying on the object key
        // alone would skip this and lose the correction silently - which is the failure the third
        // column of the unique constraint exists to prevent.
        put(key, "SKU-1,widgel,100\n");
        pass.runOnce(CatalogueIngest.TASK);

        assertThat(jdbc().queryForObject("SELECT count(*) FROM file_ingest_run", Long.class))
                .isEqualTo(2);
        assertThat(jdbc().queryForObject("SELECT name FROM catalogue WHERE sku = 'SKU-1'", String.class))
                .isEqualTo("widgel");
    }

    @Test
    @DisplayName("a corrupt record in the middle is quarantined with its offset and the rest lands")
    void quarantinesOneBadRecordAndKeepsGoing() {
        put(dropKey("catalogue-d.csv"), """
                SKU-1,widget,100
                this line has too few fields
                SKU-3,gizmo,300
                """);

        pass.runOnce(CatalogueIngest.TASK);

        assertThat(jdbc().queryForObject("SELECT count(*) FROM catalogue", Long.class)).isEqualTo(2);
        Map<String, Object> run = jdbc().queryForMap(
                "SELECT status, records_read, records_applied, records_quarantined FROM file_ingest_run");
        assertThat(run.get("status")).isEqualTo(IngestRunStatus.COMPLETED.name());
        assertThat(run.get("records_read")).isEqualTo(3L);
        assertThat(run.get("records_applied")).isEqualTo(2L);
        assertThat(run.get("records_quarantined")).isEqualTo(1L);

        Map<String, Object> quarantined = jdbc().queryForMap(
                "SELECT record_ordinal, byte_offset, stage, raw_record FROM file_ingest_quarantine");
        // Ordinal 1 is the second line, and the offset is the first byte of it: 17 characters of
        // "SKU-1,widget,100" plus its newline. A quarantine row without a usable position sends
        // whoever reads it back to the source with a line number they do not have.
        assertThat(quarantined.get("record_ordinal")).isEqualTo(1L);
        assertThat(quarantined.get("byte_offset")).isEqualTo(17L);
        assertThat(quarantined.get("stage")).isEqualTo("PARSE");
        assertThat(quarantined.get("raw_record")).isEqualTo("this line has too few fields");
    }

    @Test
    @DisplayName("a record the applier rejects is quarantined at the APPLY stage")
    void quarantinesARecordTheApplierRefuses() {
        put(dropKey("catalogue-e.csv"), "SKU-1,widget,100\n" + CatalogueApplier.POISON_SKU + ",bad,1\n");

        pass.runOnce(CatalogueIngest.TASK);

        assertThat(jdbc().queryForObject("SELECT count(*) FROM catalogue", Long.class)).isEqualTo(1);
        assertThat(jdbc().queryForObject(
                "SELECT stage FROM file_ingest_quarantine", String.class)).isEqualTo("APPLY");
        // The rest of the batch still landed: a record the applier refuses is one record, which is
        // why RecordApplier maps one row at a time rather than writing the batch itself.
        assertThat(jdbc().queryForObject("SELECT count(*) FROM catalogue", Long.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("a quarantine rate past the threshold fails the run and leaves the target untouched")
    void failsPastTheQuarantineThreshold() {
        // 200 rows, all malformed: every line parses into the wrong number of fields, which is what a
        // changed delimiter looks like. The configured ratio is 0.05.
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            body.append("SKU-").append(i).append(";widget;100\n");
        }
        put(dropKey("catalogue-f.csv"), body.toString());

        // The failure propagates out of the pass on purpose: SelfSchedulingLifecycle catches it and
        // logs it with the job's name, which is the only line that says which of several ingests
        // failed. Swallowing it here would cost exactly that.
        assertThatThrownBy(() -> pass.runOnce(CatalogueIngest.TASK))
                .isInstanceOf(QuarantineThresholdExceededException.class);

        assertThat(jdbc().queryForObject("SELECT count(*) FROM catalogue", Long.class)).isZero();
        Map<String, Object> run = jdbc().queryForMap(
                "SELECT status, failure_message FROM file_ingest_run");
        assertThat(run.get("status")).isEqualTo(IngestRunStatus.FAILED.name());
        assertThat((String) run.get("failure_message"))
                .contains("QuarantineThresholdExceededException");
    }

    @Test
    @DisplayName("the balance check gates COMPLETED, so a completed run always adds up")
    void everyCompletedRunBalances() {
        put(dropKey("catalogue-g.csv"), """
                SKU-1,widget,100
                not enough fields
                SKU-3,gizmo,300
                """ + CatalogueApplier.POISON_SKU + ",bad,1\n");

        pass.runOnce(CatalogueIngest.TASK);

        List<Map<String, Object>> runs = jdbc().queryForList(
                "SELECT status, records_read, records_applied, records_quarantined, records_skipped"
                        + " FROM file_ingest_run");
        assertThat(runs).hasSize(1);
        Map<String, Object> run = runs.get(0);
        assertThat(run.get("status")).isEqualTo(IngestRunStatus.COMPLETED.name());
        long read = (Long) run.get("records_read");
        long accounted = (Long) run.get("records_applied") + (Long) run.get("records_quarantined")
                + (Long) run.get("records_skipped");
        assertThat(accounted)
                .as("a COMPLETED run must account for every record it read; the balance check is what"
                        + " makes that a guarantee rather than a claim")
                .isEqualTo(read);
    }
}
