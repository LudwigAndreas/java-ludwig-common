package ru.ludwigandreas.ingest.actuator;

import com.querydsl.core.types.Predicate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.data.domain.PageRequest;
import ru.ludwigandreas.ingest.config.FileIngestProperties;
import ru.ludwigandreas.ingest.engine.IngestPass;
import ru.ludwigandreas.ingest.engine.IngestTaskRegistry;
import ru.ludwigandreas.ingest.engine.RegisteredIngestTask;
import ru.ludwigandreas.ingest.entity.FileIngestQuarantine;
import ru.ludwigandreas.ingest.entity.FileIngestRun;
import ru.ludwigandreas.ingest.exception.UnknownIngestTaskException;
import ru.ludwigandreas.ingest.repository.FileIngestQuarantineRepository;
import ru.ludwigandreas.ingest.repository.FileIngestRunRepository;
import ru.ludwigandreas.ingest.repository.IngestRunQueries;
import ru.ludwigandreas.webcore.operation.OperationFailure;
import ru.ludwigandreas.webcore.operation.OperationProgress;
import ru.ludwigandreas.webcore.operation.OperationResponse;
import ru.ludwigandreas.webcore.operation.OperationStatus;

/**
 * The {@code fileingest} actuator endpoint: what each task has been doing, and where its runs got to.
 *
 * <h2>What it shows, and why the checkpoint is on the list</h2>
 *
 * <p>The question asked of a batch ingest at nine in the morning is almost always one of three: did it
 * run, did it finish, and if it is still going, is it moving. The first two are the status; the third
 * is the checkpoint, and nothing else answers it. A run that has been {@code RUNNING} for forty
 * minutes with a checkpoint that is climbing is a large file; the same run with a checkpoint that has
 * not moved in ten is a problem. Without the number shown here, the two are indistinguishable from
 * outside the process.
 *
 * <p>The counts are shown next to it for the same reason the balance check keeps all four: a run that
 * is applying nothing and quarantining everything is visibly different from one that is simply slow.
 *
 * <h2>Each run is reported in the platform envelope</h2>
 *
 * <p>The {@code operation} member of each run is {@code web-core}'s {@link OperationResponse}, the
 * same shape export and notification answer with over REST. This module has no REST surface - a
 * separate task if it ever needs one - but the vocabulary and the envelope are worth adopting now,
 * because they are what makes that a rename rather than a redesign.
 *
 * <p>It replaces the loose {@code status}, {@code startedAt}, {@code finishedAt} and {@code failure}
 * members rather than sitting beside them. Duplicating four fields in an operator-facing payload to
 * avoid changing one would be paying the compatibility price of a published API on something that is
 * not one; an actuator endpoint is read by operators and dashboards this repository owns. The
 * checkpoint and the counts stay where they are, because they say more than the envelope's progress
 * can.
 *
 * <h2>Triggering a run is off by default</h2>
 *
 * <p>It is not destructive - the identity constraint still refuses an object already ingested, and the
 * checkpoint still makes a re-run resume rather than restart - but it does put a bulk load on the
 * database at a moment nobody planned for, and an endpoint that can start work is a different security
 * proposition from one that only reads. Secure the endpoint through
 * {@code security-spring-boot-starter} like any other; this class records what it did and refuses what
 * it is not allowed to do, and authenticates nobody.
 */
@Slf4j
@Endpoint(id = "fileingest")
public class FileIngestEndpoint {

    /** How many quarantine rows a run's detail view shows before it is a report rather than a view. */
    private static final int QUARANTINE_SAMPLE = 20;

    private final IngestTaskRegistry registry;
    private final FileIngestRunRepository runs;
    private final FileIngestQuarantineRepository quarantines;
    private final IngestPass pass;
    private final FileIngestProperties properties;

    /**
     * Creates the endpoint.
     *
     * @param registry    the configured tasks
     * @param runs        the run table
     * @param quarantines the quarantine table
     * @param pass        what a manual trigger runs
     * @param properties  the bound configuration
     */
    public FileIngestEndpoint(IngestTaskRegistry registry, FileIngestRunRepository runs,
                              FileIngestQuarantineRepository quarantines, IngestPass pass,
                              FileIngestProperties properties) {
        this.registry = registry;
        this.runs = runs;
        this.quarantines = quarantines;
        this.pass = pass;
        this.properties = properties;
    }

    /**
     * Every task, with its recent runs.
     *
     * @return one entry per configured task
     */
    @ReadOperation
    public Map<String, Object> tasks() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (RegisteredIngestTask task : registry.all()) {
            out.put(task.name(), describe(task));
        }
        return out;
    }

    /**
     * One task, with its recent runs and a sample of its most recent quarantine rows.
     *
     * @param task the task name
     * @return the task's state
     */
    @ReadOperation
    public Map<String, Object> task(@Selector String task) {
        return describe(registry.find(task).orElseThrow(() ->
                new UnknownIngestTaskException(task, String.join(", ", registry.names()))));
    }

    /**
     * Runs one pass now, if the estate has allowed it.
     *
     * @param task the task name
     * @return whether the pass ran on this instance
     */
    @WriteOperation
    public Map<String, Object> run(@Selector String task) {
        RegisteredIngestTask registered = registry.find(task).orElseThrow(() ->
                new UnknownIngestTaskException(task, String.join(", ", registry.names())));
        if (!properties.getEndpoint().isAllowManualRun()) {
            return Map.of("task", registered.name(), "ran", false,
                    "reason", "ludwig.ingest.endpoint.allow-manual-run is false");
        }
        log.info("Ingest task {} triggered through the actuator endpoint", registered.name());
        boolean ran = pass.runOnce(registered.name());
        return Map.of("task", registered.name(), "ran", ran,
                "reason", ran ? "completed" : "another instance holds the lock");
    }

    private Map<String, Object> describe(RegisteredIngestTask task) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", task.settings().isEnabled());
        out.put("source", task.settings().getSource().getUri());
        out.put("pattern", task.settings().getSource().getPattern());
        out.put("cron", task.settings().getSchedule().getCron());
        out.put("expectedBy", String.valueOf(task.settings().getAlert().getExpectedBy()));
        out.put("checkpointKind", task.ingest().parser().checkpointKind().name());
        out.put("runs", recentRuns(task.name()));
        return out;
    }

    private List<Map<String, Object>> recentRuns(String task) {
        Predicate predicate = IngestRunQueries.forTask(task);
        List<Map<String, Object>> out = new ArrayList<>();
        runs.findAll(predicate, PageRequest.of(0, properties.getEndpoint().getRecentRuns())
                        .withSort(org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.DESC, "startedAt")))
                .forEach(run -> out.add(describeRun(run)));
        return out;
    }

    private Map<String, Object> describeRun(FileIngestRun run) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", run.getId());
        out.put("operation", envelope(run));
        out.put("source", run.getSourceUri());
        out.put("contentIdentity", run.getContentIdentity());
        // The checkpoint, which is the only thing that distinguishes a large file from a stuck run.
        out.put("checkpoint", Map.of("kind", run.getCheckpointKind().name(),
                "position", run.getCheckpointPosition(),
                "recordsCommitted", run.getRecordsCommitted()));
        out.put("counts", Map.of("read", run.getRecordsRead(), "applied", run.getRecordsApplied(),
                "quarantined", run.getRecordsQuarantined(), "skipped", run.getRecordsSkipped(),
                "bytes", run.getBytesRead()));
        out.put("lockedBy", run.getLockedBy());
        out.put("receiptWritten", run.isReceiptWritten());
        out.put("archived", run.isArchived());
        if (run.getRecordsQuarantined() > 0) {
            out.put("quarantineSample", quarantineSample(run.getId()));
        }
        return out;
    }

    /**
     * One run in the platform envelope.
     *
     * <p>The progress deliberately has no denominator. A streaming parser does not know a file's
     * record count until it has read it, and a total invented up front is wrong for the whole run -
     * which is precisely the case {@link OperationProgress}'s nullable total exists for. The phase is
     * the checkpoint kind, because that is what a reader needs in order to interpret the position
     * next to it.
     *
     * <p>There is no result link. This module writes a receipt into the bucket rather than serving
     * one, and a link to an object store URI is not something a client of this endpoint can follow;
     * {@code receiptWritten} beside it is the honest statement. That is allowed by the contract for
     * an operation this endpoint does not publish a result resource for, and it is the concrete thing
     * a REST surface would add if this module ever grows one.
     */
    private OperationResponse envelope(FileIngestRun run) {
        return OperationResponse.builder()
                .id(run.getId().toString())
                .status(run.getStatus())
                .progress(new OperationProgress(run.getRecordsCommitted(), null, "records",
                        run.getCheckpointKind().name()))
                .submittedAt(run.getStartedAt())
                .startedAt(run.getStartedAt())
                .finishedAt(run.getFinishedAt())
                .failure(run.getStatus() == OperationStatus.FAILED && run.getFailureMessage() != null
                        ? OperationFailure.of(run.getFailureMessage())
                        : null)
                .build();
    }

    private List<Map<String, Object>> quarantineSample(UUID runId) {
        List<Map<String, Object>> out = new ArrayList<>();
        quarantines.findAll(IngestRunQueries.quarantineOf(runId),
                        PageRequest.of(0, QUARANTINE_SAMPLE)
                                .withSort(org.springframework.data.domain.Sort.by("recordOrdinal")))
                .forEach(row -> out.add(describeQuarantine(row)));
        return out;
    }

    private Map<String, Object> describeQuarantine(FileIngestQuarantine row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ordinal", row.getRecordOrdinal());
        out.put("byteOffset", row.getByteOffset());
        out.put("stage", row.getStage().name());
        out.put("error", row.getError());
        return out;
    }
}
