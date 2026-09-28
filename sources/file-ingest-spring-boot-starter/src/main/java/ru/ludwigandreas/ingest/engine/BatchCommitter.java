package ru.ludwigandreas.ingest.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.ingest.api.Checkpoint;
import ru.ludwigandreas.ingest.api.RecordApplier;
import ru.ludwigandreas.ingest.api.RecordApplyException;
import ru.ludwigandreas.ingest.bulk.StagingWriter;
import ru.ludwigandreas.ingest.config.FileIngestProperties;
import ru.ludwigandreas.ingest.entity.FileIngestQuarantine;
import ru.ludwigandreas.ingest.entity.FileIngestRun;
import ru.ludwigandreas.ingest.entity.QuarantineStage;
import ru.ludwigandreas.ingest.repository.FileIngestQuarantineRepository;
import ru.ludwigandreas.ingest.repository.FileIngestRunRepository;

/**
 * Writes one batch and advances the checkpoint, in <strong>one transaction</strong>.
 *
 * <h2>This is the entire "never miss data" argument, and it is one method</h2>
 *
 * <p>The batch and the checkpoint that claims it commit together, or neither commits. There is no
 * arrangement of two transactions that is safe, and it is worth spelling out why, because the two
 * orderings look like a performance choice and are not:
 *
 * <ul>
 *   <li><b>Data first, then checkpoint.</b> A crash in between leaves rows in staging that the
 *       checkpoint does not account for. The next run resumes from the older checkpoint, re-reads
 *       those records and writes them again - <em>duplicates</em>, which the merge may or may not
 *       collapse depending on whether the target's conflict key happens to cover them.</li>
 *   <li><b>Checkpoint first, then data.</b> A crash in between leaves a checkpoint claiming records
 *       that were never staged. The next run resumes past them and they are never read again -
 *       <em>a gap</em>, silent, permanent, and invisible to every count the module keeps, because the
 *       records were never read and so were never counted.</li>
 * </ul>
 *
 * <p>The second is the one this module exists to make impossible, and the first is not acceptable
 * either. One transaction has neither failure mode: a crash rolls back both, and the next run resumes
 * from the last checkpoint that has data behind it.
 *
 * <p><b>This is exactly the kind of thing that gets refactored apart by somebody who does not know
 * why it is together.</b> Splitting the staging write and the checkpoint update into two methods -
 * for testability, for a "cleaner" service layer, to reuse the writer somewhere else - reintroduces
 * one of the two failures above.
 *
 * <p>{@code CheckpointAtomicityIT} is the test that catches it, and it is the only one that does: the
 * resume test would pass with this annotation removed, because every write would still happen on its
 * own auto-commit. It was verified to go red when the annotation is taken off. Do not delete it, and
 * do not weaken its assertions.
 *
 * <h2>Quarantine rows are written in the same transaction, and that is also deliberate</h2>
 *
 * <p>A quarantined record is accounted for by the checkpoint just as an applied one is - the balance
 * equation counts both - so a quarantine row that committed separately could be lost while the
 * checkpoint went on claiming its record had been dealt with. The record would then be neither in the
 * target nor in the quarantine table, which is precisely the "somewhere nobody can name" the balance
 * check exists to rule out.
 */
@Slf4j
public class BatchCommitter {

    private final FileIngestRunRepository runs;
    private final FileIngestQuarantineRepository quarantines;
    private final StagingWriter stagingWriter;

    /**
     * Creates the committer.
     *
     * @param runs          the run table, whose checkpoint column this advances
     * @param quarantines   the quarantine table
     * @param stagingWriter how rows reach the staging table
     */
    public BatchCommitter(FileIngestRunRepository runs, FileIngestQuarantineRepository quarantines,
                          StagingWriter stagingWriter) {
        this.runs = runs;
        this.quarantines = quarantines;
        this.stagingWriter = stagingWriter;
    }

    /**
     * Maps, writes and checkpoints one batch.
     *
     * <p>{@code REQUIRES_NEW} rather than the default propagation, because the run loop is not itself
     * transactional and must not become so: a single transaction spanning a two-hour ingest would hold
     * one connection and one snapshot for two hours, and would make the checkpoint pointless - nothing
     * would be committed until the end, so nothing could be resumed from.
     *
     * @param <R>        the record type
     * @param runId      the run whose checkpoint this advances
     * @param task       the task name, for the quarantine rows
     * @param applier    maps records to rows
     * @param records    the batch, already bounded by count and bytes
     * @param positions  each record's ordinal and byte offset, parallel to {@code records}, for the
     *                   quarantine rows of any that cannot be mapped
     * @param next       the checkpoint this batch reaches
     * @param batchBytes how many bytes of the object this batch consumed
     * @param quarantine the task's quarantine settings, for the raw-record cap
     * @return what the batch accounted for
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @SuppressWarnings("checkstyle:ParameterNumber")
    public <R> BatchOutcome commit(UUID runId, String task, RecordApplier<R> applier, List<R> records,
                                   List<RecordPosition> positions, Checkpoint next, long batchBytes,
                                   FileIngestProperties.Quarantine quarantine) {
        List<Object[]> rows = new ArrayList<>(records.size());
        int quarantined = 0;
        int skipped = 0;
        for (int i = 0; i < records.size(); i++) {
            RecordPosition position = positions.get(i);
            try {
                Object[] row = applier.toRow(records.get(i));
                if (row == null) {
                    // A deliberate drop - a duplicate key inside the same file, typically. Counted as
                    // skipped rather than ignored, so the balance equation still adds up and the
                    // number is visible to whoever wonders why the target has fewer rows than the
                    // file had lines.
                    skipped++;
                } else {
                    rows.add(row);
                }
            } catch (RecordApplyException e) {
                quarantine(runId, task, position, QuarantineStage.APPLY, e, quarantine);
                quarantined++;
            }
        }

        int applied = rows.isEmpty() ? 0
                : stagingWriter.write(applier.stagingTable(), applier.columns(), rows);

        // The checkpoint advances here, in this same transaction, immediately after the rows it
        // accounts for. See this class's documentation before moving either half of this method.
        FileIngestRun run = runs.getByIdOrThrow(runId);
        run.setCheckpointPosition(next.position());
        run.setRecordsCommitted(next.recordsCommitted());
        // Every count advances here, including the READ count. That is not tidiness: a read count
        // kept outside this transaction is lost when a run is interrupted, while the applied count -
        // which is inside it - survives, so the resumed run reads the remainder and the two no longer
        // add up. The balance check found exactly that during this module's own development, which is
        // the argument for the balance check in one sentence.
        run.setRecordsRead(run.getRecordsRead() + records.size());
        run.setBytesRead(run.getBytesRead() + batchBytes);
        run.setRecordsApplied(run.getRecordsApplied() + applied);
        run.setRecordsQuarantined(run.getRecordsQuarantined() + quarantined);
        run.setRecordsSkipped(run.getRecordsSkipped() + skipped);
        runs.save(run);

        log.debug("Committed batch for run {}: applied={} quarantined={} skipped={} checkpoint={}",
                runId, applied, quarantined, skipped, next.position());
        return new BatchOutcome(applied, quarantined, skipped);
    }

    /**
     * Records a record that failed to parse, with its own checkpoint advance.
     *
     * <p>A parse failure happens before a record ever reaches a batch, so it cannot ride along with
     * one - but it still has to be committed together with a checkpoint that accounts for it, for the
     * reason above. This is that transaction.
     *
     * @param runId      the run
     * @param task       the task name
     * @param position   where the record was
     * @param failure    why it did not parse
     * @param next        the checkpoint reached by consuming it
     * @param recordBytes how many bytes of the object the record occupied
     * @param quarantine  the task's quarantine settings
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @SuppressWarnings("checkstyle:ParameterNumber")
    public void commitQuarantined(UUID runId, String task, RecordPosition position, Throwable failure,
                                  Checkpoint next, long recordBytes,
                                  FileIngestProperties.Quarantine quarantine) {
        quarantine(runId, task, position, stageOf(failure), failure, quarantine);
        FileIngestRun run = runs.getByIdOrThrow(runId);
        run.setCheckpointPosition(next.position());
        run.setRecordsCommitted(next.recordsCommitted());
        run.setRecordsRead(run.getRecordsRead() + 1);
        run.setBytesRead(run.getBytesRead() + recordBytes);
        run.setRecordsQuarantined(run.getRecordsQuarantined() + 1);
        runs.save(run);
    }

    private QuarantineStage stageOf(Throwable failure) {
        return failure instanceof ru.ludwigandreas.ingest.exception.PoisonRecordException
                ? QuarantineStage.OVERSIZED : QuarantineStage.PARSE;
    }

    private void quarantine(UUID runId, String task, RecordPosition position, QuarantineStage stage,
                            Throwable failure, FileIngestProperties.Quarantine settings) {
        FileIngestQuarantine row = new FileIngestQuarantine();
        row.setRunId(runId);
        row.setTask(task);
        row.setRecordOrdinal(position.ordinal());
        row.setByteOffset(position.byteOffset());
        row.setStage(stage);
        row.setRawRecord(truncate(position.raw(), settings.getMaxRecordLength().toBytes()));
        row.setError(describe(failure));
        row.setQuarantinedAt(Instant.now());
        quarantines.save(row);
    }

    /**
     * Keeps as much of the record as the task allows.
     *
     * <p>Capped because the record that failed is disproportionately likely to be the enormous one -
     * that is what a poison record is - and an untruncated quarantine table inherits exactly the
     * memory problem the batch byte bound exists to prevent, one row at a time.
     */
    private static String truncate(String raw, long maxBytes) {
        if (raw == null) {
            return null;
        }
        int limit = (int) Math.min(maxBytes, Integer.MAX_VALUE);
        return raw.length() <= limit ? raw : raw.substring(0, limit);
    }

    /**
     * The exception's type and message, which is what an operator needs and all of it.
     *
     * <p>Not the stack trace: a quarantine row is read in a list of several thousand, and a trace per
     * row would make the table unreadable and large. The trace is in the log, correlated by run id.
     */
    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
