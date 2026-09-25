package ru.ludwigandreas.ingest.engine;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.ingest.api.Checkpoint;
import ru.ludwigandreas.ingest.api.CheckpointKind;
import ru.ludwigandreas.ingest.api.FileIngest;
import ru.ludwigandreas.ingest.api.IngestRunStatus;
import ru.ludwigandreas.ingest.api.IngestRunSummary;
import ru.ludwigandreas.ingest.api.ParsedRecord;
import ru.ludwigandreas.ingest.api.RecordApplier;
import ru.ludwigandreas.ingest.api.RecordParser;
import ru.ludwigandreas.ingest.audit.IngestAuditEvent;
import ru.ludwigandreas.ingest.audit.IngestAuditLogger;
import ru.ludwigandreas.ingest.bulk.StagingMerge;
import ru.ludwigandreas.ingest.bulk.StagingWriter;
import ru.ludwigandreas.ingest.config.FileIngestProperties;
import ru.ludwigandreas.ingest.entity.FileIngestRun;
import ru.ludwigandreas.ingest.exception.IngestException;
import ru.ludwigandreas.ingest.exception.PoisonRecordException;
import ru.ludwigandreas.ingest.exception.RunTimeoutException;
import ru.ludwigandreas.ingest.metrics.IngestMetrics;
import ru.ludwigandreas.ingest.repository.FileIngestRunRepository;
import ru.ludwigandreas.ingest.repository.IngestRunQueries;
import ru.ludwigandreas.job.core.lock.RunLockHandle;
import ru.ludwigandreas.storage.api.ObjectStore;
import ru.ludwigandreas.storage.api.ObjectUri;

/**
 * Reads one object into one run.
 *
 * <h2>The loop</h2>
 *
 * <pre>
 *   open(uri, Range(checkpoint, EOF))
 *     -&gt; decompress -&gt; split into records
 *     -&gt; accumulate a batch, bounded by BOTH record count AND bytes
 *     -&gt; ONE transaction: { write batch to staging ; advance checkpoint }
 *     -&gt; renew the lock lease
 *   final: one set-based MERGE staging -&gt; target
 *     -&gt; balance check
 *     -&gt; run = COMPLETED  -&gt;  receipt object  -&gt;  archive source
 * </pre>
 *
 * <p>The single transaction in the middle is in {@link BatchCommitter}, which documents why it is one
 * and what breaks if it is split. Everything below is about the rest of the shape.
 *
 * <h2>Nothing here materialises the object</h2>
 *
 * <p>The stream is opened and handed to the parser, which returns an iterator. Records are pulled one
 * at a time into an accumulator bounded in bytes as well as in records, and the accumulator is emptied
 * after every flush. At no point does this class hold more than one batch, and a batch is capped at
 * {@code batch.max-bytes}. There is no {@code readAllBytes}, no {@code List} of the file's records,
 * and no collection that grows with the object's size; an ArchUnit rule in this module's test suite
 * forbids the first two by name, and the integration suite runs a 200 MB object under a measured heap
 * ceiling, because a requirement worth stating is a requirement worth testing.
 *
 * <h2>The ordering of the side effects at the end cannot be changed</h2>
 *
 * <p>{@code COMPLETED} in the database <em>first</em>, because it is the source of truth. Then the
 * receipt object. Then the archive. A crash at any point after the status re-runs, finds the run
 * already complete through the identity constraint, and redoes only the bucket operations - which are
 * idempotent, because copying an object over itself and deleting something already absent both
 * succeed.
 *
 * <p>The reverse order is what looks natural and is wrong: archive the source, then mark the run. A
 * crash in between leaves a bucket that says "processed" and a database that says the run never
 * happened, and the next pass finds no object to ingest and no record that it ever was.
 */
@Slf4j
public class IngestRunner {

    /** Records that must have been read before a quarantine ratio means anything. */
    private static final long RATIO_MINIMUM_RECORDS = 100;

    /** How much of a failure message the run row keeps; the column is VARCHAR(4000). */
    private static final int FAILURE_MESSAGE_LIMIT = 4000;

    private final ObjectStore store;
    private final FileIngestRunRepository runs;
    private final BatchCommitter committer;
    private final StagingWriter stagingWriter;
    private final StagingMerge merge;
    private final ReceiptWriter receipts;
    private final SourceArchiver archiver;
    private final IngestMetrics metrics;
    private final IngestAuditLogger audit;
    private final Clock clock;

    /**
     * Creates the runner.
     *
     * @param store         where the source objects are
     * @param runs          the run table
     * @param committer     the batch-and-checkpoint transaction
     * @param stagingWriter how rows reach staging
     * @param merge         the staging-to-target merge
     * @param receipts      the receipt writer
     * @param archiver      the source archiver
     * @param metrics       the metrics
     * @param audit         the audit sink
     * @param clock         the clock
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - a Spring bean assembled in one @Bean method, where every
    // argument is named by its own bean; there is no positional call site for the rule to protect.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public IngestRunner(ObjectStore store, FileIngestRunRepository runs, BatchCommitter committer,
                        StagingWriter stagingWriter, StagingMerge merge, ReceiptWriter receipts,
                        SourceArchiver archiver, IngestMetrics metrics, IngestAuditLogger audit,
                        Clock clock) {
        this.store = store;
        this.runs = runs;
        this.committer = committer;
        this.stagingWriter = stagingWriter;
        this.merge = merge;
        this.receipts = receipts;
        this.archiver = archiver;
        this.metrics = metrics;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Ingests one object, resuming an interrupted run or starting a new one.
     *
     * @param task      the task
     * @param candidate the object, already confirmed to have arrived
     * @param handle    the held run lock, whose lease this renews between batches
     * @return the run's summary
     */
    public IngestRunSummary ingest(RegisteredIngestTask task, ObjectCandidate candidate,
                                   RunLockHandle handle) {
        FileIngestRun run = claimOrResume(task, candidate, handle);
        Checkpoint checkpoint = Checkpoint.of(run.getCheckpointKind(), run.getCheckpointPosition(),
                run.getRecordsCommitted());
        boolean fresh = checkpoint.position() == 0 && checkpoint.recordsCommitted() == 0;

        audit.record(new IngestAuditEvent(clock.instant(), run.getId(), task.name(),
                fresh ? IngestAuditEvent.CLAIMED : IngestAuditEvent.RESUMED,
                Map.of("uri", candidate.uri().value(), "checkpoint", checkpoint.position())));

        if (fresh && task.settings().getWrite().isTruncateStagingOnFreshRun()) {
            // Only when fresh. A resumed run must not truncate: the staged rows are exactly the work
            // its checkpoint says has already been done, and removing them would lose every record
            // before the resume point while the checkpoint went on claiming they were written. That
            // is the one way this module could lose data with no individual step being wrong, which
            // is why the decision is taken from the checkpoint rather than from configuration.
            stagingWriter.truncate(applierOf(task).stagingTable());
        }

        Instant startedAt = run.getStartedAt();
        Instant attemptStartedAt = clock.instant();
        LeaseRenewer renewer = new LeaseRenewer(handle, task.name(), task.settings().getLock(),
                attemptStartedAt);
        try {
            read(task, candidate, run, checkpoint, renewer, attemptStartedAt);
            // complete() is inside the same try on purpose. The balance check lives in there, and a
            // run that cannot balance is the most important thing in this module to record as FAILED:
            // left RUNNING it would look to an operator like a run still in progress, and the next
            // pass would resume a run that has already read the whole object.
            return complete(task, candidate, run, startedAt);
        } catch (RuntimeException e) {
            fail(run, task, e);
            throw e;
        }
    }

    /**
     * The batch loop.
     *
     * <p>Generic over the record type so that the parser and the applier are known to agree: the
     * registry holds a {@code FileIngest<?>}, and this is where the wildcard is captured once rather
     * than cast at every use.
     */
    private <R> void read(RegisteredIngestTask task, ObjectCandidate candidate, FileIngestRun run,
                          Checkpoint start, LeaseRenewer renewer, Instant attemptStartedAt) {
        @SuppressWarnings("unchecked")
        FileIngest<R> ingest = (FileIngest<R>) task.ingest();
        RecordParser<R> parser = ingest.parser();
        RecordApplier<R> applier = ingest.applier();
        FileIngestProperties.Task settings = task.settings();

        SourceStreams.Opened opened = SourceStreams.open(store, candidate.uri(), start,
                settings.getSource().getCompression());
        Checkpoint checkpoint = start;

        BatchAccumulator<R> batch = new BatchAccumulator<>(settings.getBatch().getMaxRecords(),
                settings.getBatch().getMaxBytes().toBytes());
        List<RecordPosition> positions = new ArrayList<>();

        try (InputStream stream = opened.stream()) {
            Iterator<ParsedRecord<R>> records = iterator(parser, stream, opened, start);
            while (records.hasNext()) {
                ParsedRecord<R> record = records.next();

                if (!record.ok()) {
                    // The batch is flushed first, so that the quarantine row's checkpoint does not
                    // claim records still sitting in the accumulator.
                    flushAt(task, run, applier, batch, positions, checkpoint);
                    checkpoint = advance(checkpointAfter(checkpoint, batch), record, 1);
                    committer.commitQuarantined(run.getId(), task.name(), positionOf(record),
                            record.failure(), checkpoint, record.sizeBytes(),
                            settings.getQuarantine());
                    batch.clear();
                    positions.clear();
                    afterRecord(task, run, renewer, attemptStartedAt);
                    continue;
                }

                if (batch.wouldOverflowAlone(record)) {
                    // A poison record: larger than any batch could hold, so quarantined with its
                    // offset rather than grown into. Growing is an OutOfMemoryError that names none
                    // of four million rows.
                    flushAt(task, run, applier, batch, positions, checkpoint);
                    checkpoint = advance(checkpointAfter(checkpoint, batch), record, 1);
                    committer.commitQuarantined(run.getId(), task.name(), positionOf(record),
                            new PoisonRecordException(record.ordinal(), record.sizeBytes(),
                                    settings.getBatch().getMaxBytes().toBytes()),
                            checkpoint, record.sizeBytes(), settings.getQuarantine());
                    batch.clear();
                    positions.clear();
                    afterRecord(task, run, renewer, attemptStartedAt);
                    continue;
                }

                if (batch.shouldFlushBefore(record)) {
                    checkpoint = checkpointAfter(checkpoint, batch);
                    flushAt(task, run, applier, batch, positions, checkpoint);
                    batch.clear();
                    positions.clear();
                    afterRecord(task, run, renewer, attemptStartedAt);
                }
                batch.add(record);
                positions.add(positionOf(record));
            }

            if (!batch.isEmpty()) {
                checkpoint = checkpointAfter(checkpoint, batch);
                flushAt(task, run, applier, batch, positions, checkpoint);
                batch.clear();
                positions.clear();
            }
        } catch (IOException e) {
            throw new IngestException("Could not read " + candidate.uri().value(), e);
        }
    }

    private <R> Iterator<ParsedRecord<R>> iterator(RecordParser<R> parser, InputStream stream,
                                                   SourceStreams.Opened opened, Checkpoint start) {
        if (!opened.mustSkipForward()) {
            return parser.parse(stream, opened.startOffset(), start.recordsCommitted());
        }
        if (start.kind() == CheckpointKind.RECORD_ORDINAL) {
            return parser.parseFromOrdinal(stream, start.recordsCommitted());
        }
        // A byte-offset checkpoint on a stream that could not be positioned - a gzipped object. The
        // offset counts uncompressed bytes, and the only way to reach one in a gzip stream is to
        // decompress everything before it, so the parser is started from zero and its records are
        // discarded until the offset is passed. Correct, and it costs the transfer; see SourceStreams.
        Iterator<ParsedRecord<R>> all = parser.parse(stream, 0, 0);
        return new SkippingIterator<>(all, start.position());
    }

    /**
     * Commits one batch at a checkpoint the caller has already computed.
     *
     * <p>Does not clear the accumulator: the caller does that, after the commit returns. Clearing
     * first would lose the batch if the commit failed, and the checkpoint would not have moved - so
     * the records would be neither written nor re-read.
     */
    private <R> void flushAt(RegisteredIngestTask task, FileIngestRun run, RecordApplier<R> applier,
                             BatchAccumulator<R> batch, List<RecordPosition> positions,
                             Checkpoint checkpoint) {
        if (batch.isEmpty()) {
            return;
        }
        long bytes = batch.bytes();
        Instant started = clock.instant();
        BatchOutcome outcome = committer.commit(run.getId(), task.name(), applier, batch.values(),
                List.copyOf(positions), checkpoint, bytes, task.settings().getQuarantine());
        metrics.recordBytesRead(task.name(), bytes);
        metrics.recordBatchFlush(task.name(), Duration.between(started, clock.instant()));
        metrics.recordRecords(task.name(), outcome.accounted(), outcome.applied(),
                outcome.quarantined(), outcome.skipped());
        audit.record(new IngestAuditEvent(clock.instant(), run.getId(), task.name(),
                IngestAuditEvent.BATCH_COMMITTED,
                Map.of("applied", outcome.applied(), "checkpoint", checkpoint.position())));
    }

    /**
     * What happens between records: the lease is renewed, and the quarantine policy is applied.
     *
     * <p>The policy is checked here rather than only at the end so that a file which is wholly wrong
     * fails in the first few seconds instead of after forty minutes of quarantining every row it
     * reads.
     */
    private void afterRecord(RegisteredIngestTask task, FileIngestRun run, LeaseRenewer renewer,
                             Instant attemptStartedAt) {
        FileIngestRun latest = runs.getByIdOrThrow(run.getId());
        QuarantinePolicyCheck.verify(task.name(), latest.getRecordsRead(),
                latest.getRecordsQuarantined(), RATIO_MINIMUM_RECORDS,
                task.settings().getQuarantine());
        Instant now = clock.instant();
        // The wall-clock budget, checked between batches alongside the lease. A configured
        // run-timeout that nothing enforced would be worse than none: it would make an operator
        // believe there is a guard where there is not one. See RunTimeoutException for why the socket
        // timeout does not cover this.
        Duration elapsed = Duration.between(attemptStartedAt, now);
        Duration budget = task.settings().getSchedule().getRunTimeout();
        if (elapsed.compareTo(budget) > 0) {
            throw new RunTimeoutException(task.name(), elapsed, budget, latest.getRecordsCommitted());
        }
        renewer.renewIfDue(now, latest.getRecordsCommitted());
    }

    private Checkpoint advance(Checkpoint current, ParsedRecord<?> record, long records) {
        return current instanceof Checkpoint.ByteOffset offset
                ? offset.advancedTo(record.endOffset(), records)
                : ((Checkpoint.RecordOrdinal) current).advancedBy(records);
    }

    private Checkpoint checkpointAfter(Checkpoint current, BatchAccumulator<?> batch) {
        if (batch.isEmpty()) {
            return current;
        }
        return current instanceof Checkpoint.ByteOffset offset
                ? offset.advancedTo(batch.endOffset(), batch.size())
                : ((Checkpoint.RecordOrdinal) current).advancedBy(batch.size());
    }

    private RecordPosition positionOf(ParsedRecord<?> record) {
        return new RecordPosition(record.ordinal(), record.startOffset(), record.raw());
    }

    /**
     * Finds the existing run for this object, or creates one.
     *
     * <p>The lookup is by the identity triple, which is also the unique constraint - so a run that
     * already {@code COMPLETED} is found here and the caller skips. Asking first rather than inserting
     * and interpreting the constraint violation is a readability choice: both are correct, and the
     * second puts a rolled-back transaction in the log of every ordinary re-offer. The constraint is
     * still what guarantees the outcome when two replicas ask in the same moment.
     */
    private FileIngestRun claimOrResume(RegisteredIngestTask task, ObjectCandidate candidate,
                                        RunLockHandle handle) {
        ObjectUri uri = candidate.uri();
        Optional<FileIngestRun> existing = runs.findOne(IngestRunQueries.byIdentity(
                uri.container(), uri.key(), candidate.contentIdentity()));
        FileIngestRun run = existing.orElseGet(FileIngestRun::new);
        if (existing.isEmpty()) {
            run.setTask(task.name());
            run.setContainer(uri.container());
            run.setObjectKey(uri.key());
            run.setContentIdentity(candidate.contentIdentity());
            run.setSourceUri(uri.value());
            run.setCheckpointKind(task.ingest().parser().checkpointKind());
            run.setStartedAt(clock.instant());
            run.setExpectedRecords(candidate.expectedRecords());
        }
        run.setStatus(IngestRunStatus.RUNNING);
        run.setLockedBy(handle.owner());
        run.setFinishedAt(null);
        run.setFailureMessage(null);
        return runs.save(run);
    }

    /**
     * Merges, balances, and then does the side effects in the one order that is safe.
     */
    private IngestRunSummary complete(RegisteredIngestTask task, ObjectCandidate candidate,
                                      FileIngestRun run, Instant startedAt) {
        RecordApplier<?> applier = applierOf(task);
        FileIngestRun latest = runs.getByIdOrThrow(run.getId());

        Instant mergeStarted = clock.instant();
        long staged = merge.countStaged(applier.stagingTable());
        IngestRunSummary provisional = summaryOf(latest, candidate, startedAt, null);

        // Balance BEFORE the merge, so that a run which cannot account for its records leaves the
        // target untouched. Checking afterwards would make the check a report on damage already done.
        BalanceCheck.verify(provisional, latest.getExpectedRecords());
        BalanceCheck.verifyStaged(provisional, staged);

        int merged = merge.merge(applier.mergeStatement());
        metrics.recordMerge(task.name(), Duration.between(mergeStarted, clock.instant()));
        audit.record(new IngestAuditEvent(clock.instant(), run.getId(), task.name(),
                IngestAuditEvent.MERGED, Map.of("targetRows", merged, "staged", staged)));

        // 1. The database, because it is the source of truth.
        latest.setStatus(IngestRunStatus.COMPLETED);
        latest.setFinishedAt(clock.instant());
        latest = runs.save(latest);
        IngestRunSummary summary = summaryOf(latest, candidate, startedAt, latest.getFinishedAt());
        metrics.recordRun(task.name(), IngestRunStatus.COMPLETED, summary.duration());
        audit.record(new IngestAuditEvent(clock.instant(), run.getId(), task.name(),
                IngestAuditEvent.COMPLETED, Map.of("records", summary.recordsApplied())));

        // 2. The receipt, and 3. the archive. Both idempotent, both redone by a re-run that finds the
        // status already COMPLETED, and both after the status for the reason in this class's note.
        //
        // Each flag is written on a freshly loaded row. These are three separate transactions by
        // design - the status must be durable before the bucket is touched - and a single detached
        // entity carried across them would be writing a version the database has already moved past,
        // which Hibernate reports as an optimistic-lock failure on a run that in fact succeeded.
        if (receipts.write(task, candidate, summary)) {
            markDone(run.getId(), FileIngestRun::setReceiptWritten);
        }
        if (archiver.archive(task, candidate)) {
            markDone(run.getId(), FileIngestRun::setArchived);
        }
        return summary;
    }

    /**
     * Records one of the post-completion flags on a freshly loaded row.
     *
     * @param runId the run
     * @param flag  which flag to set
     */
    private void markDone(UUID runId, BiConsumer<FileIngestRun, Boolean> flag) {
        FileIngestRun row = runs.getByIdOrThrow(runId);
        flag.accept(row, true);
        runs.save(row);
    }

    private void fail(FileIngestRun run, RegisteredIngestTask task, RuntimeException failure) {
        FileIngestRun latest = runs.getByIdOrThrow(run.getId());
        latest.setStatus(IngestRunStatus.FAILED);
        latest.setFinishedAt(clock.instant());
        // The whole message, because for a balance failure the numbers in it are the diagnosis.
        latest.setFailureMessage(abbreviate(failure.toString()));
        runs.save(latest);
        metrics.recordRun(task.name(), IngestRunStatus.FAILED,
                Duration.between(latest.getStartedAt(), latest.getFinishedAt()));
        audit.record(new IngestAuditEvent(clock.instant(), run.getId(), task.name(),
                IngestAuditEvent.FAILED, Map.of("reason", failure.getClass().getSimpleName())));
        log.error("Ingest run {} for task {} failed", run.getId(), task.name(), failure);
    }

    private IngestRunSummary summaryOf(FileIngestRun run, ObjectCandidate candidate, Instant startedAt,
                                       Instant finishedAt) {
        return new IngestRunSummary(run.getId(), run.getTask(), candidate.uri().value(),
                run.getContentIdentity(), run.getBytesRead(), run.getRecordsRead(),
                run.getRecordsApplied(), run.getRecordsQuarantined(), run.getRecordsSkipped(),
                startedAt, finishedAt);
    }

    @SuppressWarnings("unchecked")
    private static RecordApplier<Object> applierOf(RegisteredIngestTask task) {
        return (RecordApplier<Object>) task.ingest().applier();
    }

    private static String abbreviate(String message) {
        return message.length() <= FAILURE_MESSAGE_LIMIT
                ? message : message.substring(0, FAILURE_MESSAGE_LIMIT);
    }

    /**
     * Discards records until a byte offset is reached, for a compressed resume.
     *
     * <p>Its own type rather than a loop, because it has to be lazy: pulling every record before the
     * offset into a list first would be exactly the materialisation this module forbids, and on a
     * resume near the end of a large file it would be the whole file.
     */
    private static final class SkippingIterator<R> implements Iterator<ParsedRecord<R>> {

        private final Iterator<ParsedRecord<R>> delegate;
        private final long untilOffset;
        private ParsedRecord<R> next;

        private SkippingIterator(Iterator<ParsedRecord<R>> delegate, long untilOffset) {
            this.delegate = delegate;
            this.untilOffset = untilOffset;
        }

        @Override
        public boolean hasNext() {
            while (next == null && delegate.hasNext()) {
                ParsedRecord<R> candidate = delegate.next();
                if (candidate.startOffset() >= untilOffset) {
                    next = candidate;
                }
            }
            return next != null;
        }

        @Override
        public ParsedRecord<R> next() {
            if (!hasNext()) {
                throw new java.util.NoSuchElementException("No records past offset " + untilOffset);
            }
            ParsedRecord<R> value = next;
            next = null;
            return value;
        }
    }
}
