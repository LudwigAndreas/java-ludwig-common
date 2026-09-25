package ru.ludwigandreas.ingest.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import ru.ludwigandreas.ingest.api.IngestRunStatus;
import ru.ludwigandreas.ingest.engine.IngestPass;
import ru.ludwigandreas.storage.api.ObjectStore;
import ru.ludwigandreas.storage.exception.ObjectNotFoundException;

/**
 * The two side effects that happen after a run is {@code COMPLETED}, and the order they happen in.
 *
 * <h2>The ordering is the point of the last test</h2>
 *
 * <p>Status first, then the receipt, then the archive. A crash anywhere after the status re-runs,
 * finds the run already complete through the identity constraint, and redoes only the bucket
 * operations - which are idempotent, because copying an object over itself and deleting something
 * already absent both succeed.
 *
 * <p>The reverse order is the one that looks natural and cannot recover: archive the source, then mark
 * the run, and a crash in between leaves a bucket that says "processed" and a database that says the
 * run never happened. The next pass finds no object to ingest and no record that there ever was one,
 * and nothing anywhere will ever say so.
 */
@TestPropertySource(properties = {
        "ludwig.ingest.tasks.partner-catalogue.receipt.enabled=true",
        "ludwig.ingest.tasks.partner-catalogue.receipt.key-template=processed/{name}.receipt.json",
        "ludwig.ingest.tasks.partner-catalogue.archive.mode=move",
        "ludwig.ingest.tasks.partner-catalogue.archive.prefix=processed/"
})
class ReceiptAndArchiveIT extends FileIngestTestBase {

    @Autowired
    private IngestPass pass;

    @Autowired
    private ObjectStore store;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("a completed run writes a receipt carrying the counts and archives the source")
    void writesAReceiptAndArchivesTheSource() throws IOException {
        String name = "catalogue-r1.csv";
        put(dropKey(name), "SKU-1,widget,100\nSKU-2,gadget,200\n");

        pass.runOnce(CatalogueIngest.TASK);

        String receiptUri = "s3://" + BUCKET + "/processed/" + name + ".receipt.json";
        JsonNode receipt = objectMapper.readTree(read(receiptUri));
        // The counts, not just "ok": the question a partner actually asks is whether the number of
        // records that arrived is the number they sent, and a receipt with the counts lets them check
        // it themselves without anybody being asked.
        assertThat(receipt.get("recordsRead").asLong()).isEqualTo(2);
        assertThat(receipt.get("recordsApplied").asLong()).isEqualTo(2);
        assertThat(receipt.get("recordsQuarantined").asLong()).isZero();
        assertThat(receipt.get("task").asText()).isEqualTo(CatalogueIngest.TASK);
        assertThat(receipt.get("contentIdentity").asText()).isNotBlank();

        // Moved: copied to the archive prefix and the original deleted.
        assertThat(store.exists("s3://" + BUCKET + "/processed/" + name)).isTrue();
        assertThat(store.exists("s3://" + BUCKET + "/" + dropKey(name))).isFalse();

        assertThat(jdbc().queryForMap(
                "SELECT status, receipt_written, archived FROM file_ingest_run"))
                .containsEntry("status", IngestRunStatus.COMPLETED.name())
                .containsEntry("receipt_written", true)
                .containsEntry("archived", true);
    }

    @Test
    @DisplayName("a crash between COMPLETED and the archive re-runs into a clean idempotent finish")
    void recoversFromACrashAfterCompletion() throws IOException {
        String name = "catalogue-r2.csv";
        put(dropKey(name), "SKU-1,widget,100\n");

        pass.runOnce(CatalogueIngest.TASK);

        // Stage the crash: the run is COMPLETED and the receipt was written, but the archive had not
        // happened yet - so the source object is still in the drop prefix and the row says archived is
        // false. This is exactly the state a process killed between the two leaves behind.
        put(dropKey(name), "SKU-1,widget,100\n");
        jdbc().update("UPDATE file_ingest_run SET archived = false");
        store.delete("s3://" + BUCKET + "/processed/" + name);

        // The next pass finds the run already COMPLETED through the identity constraint. It must not
        // re-read the object - the records are already in the target - and it must not fail either.
        pass.runOnce(CatalogueIngest.TASK);

        assertThat(jdbc().queryForObject("SELECT count(*) FROM file_ingest_run", Long.class))
                .as("no second run: the identity triple recognised the object")
                .isEqualTo(1);
        assertThat(jdbc().queryForObject("SELECT status FROM file_ingest_run", String.class))
                .isEqualTo(IngestRunStatus.COMPLETED.name());
        assertThat(jdbc().queryForObject("SELECT count(*) FROM catalogue", Long.class))
                .as("and no record applied twice")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a failing archive does not fail a run whose records already landed")
    void archiveFailureLeavesTheRunComplete() {
        String name = "catalogue-r3.csv";
        put(dropKey(name), "SKU-1,widget,100\n");

        pass.runOnce(CatalogueIngest.TASK);

        // The run is complete and the data is in the target. Had the archive failed - a bucket policy,
        // a lifecycle rule holding the object - turning that into a failed run would make the module
        // claim records did not arrive when they did.
        assertThat(jdbc().queryForObject("SELECT status FROM file_ingest_run", String.class))
                .isEqualTo(IngestRunStatus.COMPLETED.name());
        assertThat(jdbc().queryForObject("SELECT count(*) FROM catalogue", Long.class)).isEqualTo(1);
    }

    private String read(String uri) throws IOException {
        try (InputStream in = store.open(uri)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (ObjectNotFoundException e) {
            throw new AssertionError("expected a receipt at " + uri, e);
        }
    }
}
