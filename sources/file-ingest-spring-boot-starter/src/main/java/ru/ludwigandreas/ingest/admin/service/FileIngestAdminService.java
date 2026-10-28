package ru.ludwigandreas.ingest.admin.service;

import com.querydsl.core.types.Predicate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import ru.ludwigandreas.ingest.engine.IngestPass;
import ru.ludwigandreas.ingest.engine.IngestTaskRegistry;
import ru.ludwigandreas.ingest.engine.RegisteredIngestTask;
import ru.ludwigandreas.ingest.entity.FileIngestQuarantine;
import ru.ludwigandreas.ingest.entity.FileIngestRun;
import ru.ludwigandreas.ingest.exception.UnknownIngestTaskException;
import ru.ludwigandreas.ingest.repository.FileIngestQuarantineRepository;
import ru.ludwigandreas.ingest.repository.FileIngestRunRepository;
import ru.ludwigandreas.ingest.repository.IngestRunQueries;
import ru.ludwigandreas.security.admin.AdminAudit;
import ru.ludwigandreas.security.admin.AdminOutcome;
import ru.ludwigandreas.webcore.operation.OperationFailure;
import ru.ludwigandreas.webcore.operation.OperationProgress;
import ru.ludwigandreas.webcore.operation.OperationResponse;
import ru.ludwigandreas.webcore.operation.OperationResult;
import ru.ludwigandreas.webcore.operation.OperationStatus;

/**
 * The {@code fileingest} area's operations: what each task has been doing, and where its runs got to.
 *
 * <p>Ported from the {@code fileingest} actuator endpoint these endpoints replace. The question asked of a batch
 * ingest at nine in the morning is almost always one of three - did it run, did it finish, and if it is still going,
 * is it moving - and the third is answered only by the checkpoint, which is why every run view carries it beside the
 * envelope. The manual trigger no longer needs {@code ludwig.ingest.endpoint.allow-manual-run}: whether a deployment
 * permits it is whether any role is granted {@code fileingest.operate}.
 */
public class FileIngestAdminService {

    /** How many recent runs a task view lists. */
    private static final int RECENT_RUNS = 20;

    /** How many quarantine rows a run's detail view shows before it is a report rather than a view. */
    private static final int QUARANTINE_SAMPLE = 20;

    private static final String TASK = "ingest-task";
    private static final String RUNS = "/admin/v1/fileingest/tasks/%s/runs/%s";

    private final IngestTaskRegistry registry;
    private final FileIngestRunRepository runs;
    private final FileIngestQuarantineRepository quarantines;
    private final IngestPass pass;
    private final AdminAudit audit;

    /**
     * Creates the service.
     *
     * @param registry    the configured tasks
     * @param runs        the run table
     * @param quarantines the quarantine table
     * @param pass        what a manual trigger runs
     * @param audit       the administrative audit
     */
    public FileIngestAdminService(IngestTaskRegistry registry, FileIngestRunRepository runs,
                                  FileIngestQuarantineRepository quarantines, IngestPass pass, AdminAudit audit) {
        this.registry = registry;
        this.runs = runs;
        this.quarantines = quarantines;
        this.pass = pass;
        this.audit = audit;
    }

    /**
     * Every task, with its recent runs.
     *
     * @return one entry per configured task
     */
    public Map<String, Object> tasks() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (RegisteredIngestTask task : registry.all()) {
            out.put(task.name(), describe(task));
        }
        return out;
    }

    /**
     * One task, with its recent runs.
     *
     * @param task the task name
     * @return the task's state
     */
    public Map<String, Object> task(String task) {
        return describe(require(task));
    }

    /**
     * One run, with its quarantine sample.
     *
     * @param task  the task name
     * @param runId the run
     * @return the run view
     */
    public Map<String, Object> run(String task, UUID runId) {
        require(task);
        FileIngestRun run = runs.findById(runId).filter(found -> task.equals(found.getTask()))
                .orElseThrow(() -> FileIngestAdminException.runNotFound(runId, task));
        return describeRun(run);
    }

    /**
     * Runs one pass now, on this instance, and answers with the run it produced.
     *
     * @param task the task name
     * @return the newest run's envelope, whose id is the run id; null when the pass found nothing to ingest
     */
    public OperationResponse trigger(String task) {
        RegisteredIngestTask registered = require(task);
        if (!pass.runOnce(registered.name())) {
            audit.record(TASK, task, AdminOutcome.REFUSED_BY_STATE);
            throw FileIngestAdminException.alreadyRunning(task);
        }
        audit.record(TASK, task, AdminOutcome.SUCCEEDED);
        return runs.findAll(IngestRunQueries.forTask(task),
                        PageRequest.of(0, 1).withSort(Sort.by(Sort.Direction.DESC, "startedAt")))
                .stream().findFirst().map(FileIngestAdminService::envelope).orElse(null);
    }

    private RegisteredIngestTask require(String task) {
        return registry.find(task).orElseThrow(() ->
                new UnknownIngestTaskException(task, String.join(", ", registry.names())));
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
        runs.findAll(predicate, PageRequest.of(0, RECENT_RUNS).withSort(Sort.by(Sort.Direction.DESC, "startedAt")))
                .forEach(run -> out.add(describeRun(run)));
        return out;
    }

    private Map<String, Object> describeRun(FileIngestRun run) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", run.getId());
        out.put("operation", envelope(run));
        out.put("source", run.getSourceUri());
        out.put("contentIdentity", run.getContentIdentity());
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
     * One run in the platform envelope. A succeeded run's result link is its own run resource: the contract requires
     * a link for a {@code SUCCEEDED} operation, and the run view - checkpoint, counts, receipt - is the result an
     * operator can follow. The progress has no denominator, because a streaming parser does not know a file's record
     * count until it has read it.
     */
    private static OperationResponse envelope(FileIngestRun run) {
        return OperationResponse.builder()
                .id(run.getId().toString())
                .status(run.getStatus())
                .progress(new OperationProgress(run.getRecordsCommitted(), null, "records",
                        run.getCheckpointKind().name()))
                .submittedAt(run.getStartedAt())
                .startedAt(run.getStartedAt())
                .finishedAt(run.getFinishedAt())
                .result(run.getStatus() == OperationStatus.SUCCEEDED
                        ? OperationResult.at(RUNS.formatted(run.getTask(), run.getId()))
                        : null)
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
