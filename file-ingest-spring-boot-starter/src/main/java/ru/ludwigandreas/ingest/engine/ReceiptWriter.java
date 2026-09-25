package ru.ludwigandreas.ingest.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.ingest.api.IngestRunSummary;
import ru.ludwigandreas.ingest.config.FileIngestProperties;
import ru.ludwigandreas.storage.api.ObjectStore;
import ru.ludwigandreas.storage.api.ObjectUri;
import ru.ludwigandreas.storage.api.PutOptions;
import ru.ludwigandreas.storage.exception.ObjectStoreException;

/**
 * Writes the receipt object a successful run leaves behind.
 *
 * <h2>"Send a done file" is two features, and this is the outbound one</h2>
 *
 * <p>Consuming a partner's sentinel and producing one of our own share a name and nothing else. The
 * inbound half is {@link ArrivalDetector}, it is on by default, and it exists to stop this service
 * reading a half-written object. This half is off by default, and it exists to tell a partner that
 * their file was processed and what happened to it. A service that conflated them would either write
 * receipts into buckets nobody reads, or refuse to read from a partner who does not want one.
 *
 * <h2>Why the receipt carries the counts and not just "ok"</h2>
 *
 * <p>Because the question a partner actually asks is not whether the file was accepted - the absence
 * of a complaint answers that - but whether the number of records that arrived is the number they
 * sent. A receipt with the counts lets them check that themselves, on their side, without anybody
 * being asked; and the quarantine count in it is how they find out that four thousand of their rows
 * were rejected before somebody notices downstream.
 */
@Slf4j
public class ReceiptWriter {

    private final ObjectStore store;
    private final ObjectMapper objectMapper;

    /**
     * Creates the writer.
     *
     * @param store        where the receipt goes
     * @param objectMapper how the summary becomes JSON
     */
    public ReceiptWriter(ObjectStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    /**
     * Writes a receipt, if the task wants one.
     *
     * <p>A failure here is logged and swallowed rather than failing the run, and that is the same
     * reasoning as the ordering in {@code IngestRunner}: the run is already {@code COMPLETED}, the
     * data is already in the target, and turning a bucket permission problem into a failed run would
     * make the module claim that records did not arrive when they did. The run row records whether
     * the receipt was written, so a re-run can finish the job.
     *
     * @param task      the task
     * @param candidate the object that was ingested
     * @param summary   what the run did
     * @return {@code true} if a receipt was written
     */
    public boolean write(RegisteredIngestTask task, ObjectCandidate candidate, IngestRunSummary summary) {
        FileIngestProperties.Receipt receipt = task.settings().getReceipt();
        if (!receipt.isEnabled()) {
            return false;
        }
        ObjectUri target = IngestNaming.receiptFor(candidate.uri(), receipt.getKeyTemplate());
        Path temp = null;
        try {
            // Through a temp file rather than a byte array, because ObjectStore#put takes a Path: a
            // store needs the content length before it sends the first byte, and the only way to get
            // one from a stream is to buffer the whole thing. A receipt is small, so this is cheap -
            // and it keeps one rule rather than two about how objects are written.
            temp = Files.createTempFile("ludwig-ingest-receipt", ".json");
            Files.writeString(temp, objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(summary), StandardOpenOption.TRUNCATE_EXISTING);
            store.put(target.value(), temp, PutOptions.ofContentType("application/json"));
            log.info("Wrote ingest receipt for run {} to {}", summary.runId(), target.value());
            return true;
        } catch (JsonProcessingException e) {
            log.warn("Could not render the ingest receipt for run {}: {}", summary.runId(), e.toString());
            return false;
        } catch (IOException | ObjectStoreException e) {
            log.warn("Could not write the ingest receipt for run {} to {}: {}", summary.runId(),
                    target.value(), e.toString());
            return false;
        } finally {
            deleteQuietly(temp);
        }
    }


    private void deleteQuietly(Path temp) {
        if (temp == null) {
            return;
        }
        try {
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            log.debug("Could not remove the temporary receipt file {}: {}", temp, e.toString());
        }
    }
}
